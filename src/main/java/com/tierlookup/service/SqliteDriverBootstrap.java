package com.tierlookup.service;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;


/**
* Loads Xerial SQLite JDBC without making TierLookup's manual Fabric build depend on a local Gradle cache.
* The two small classifier jars are installed once into .minecraft/tierlists/lib, checksum verified, then
* all database work is offline. This bootstrap is never reachable from hover/render/tick hot paths.
*/ final class SqliteDriverBootstrap {
    static final String VERSION="3.53.2.1";
    private static final String BASE="https://github.com/xerial/sqlite-jdbc/releases/download/"+VERSION+"/";
    private static final Artifact CLASSES=new Artifact("sqlite-jdbc-"+VERSION+"-without-natives.jar", "4baeeb32cfb8ac3e5922e0fe50b8f78e4fe39d00f68824ce677a9477c686715b");
    private static final AtomicReference<Driver> DRIVER=new AtomicReference<>();
    private static volatile ClassLoader loader;
    private record Artifact(String name, String sha256) {
    }
    private SqliteDriverBootstrap() {
    }
    static Connection connect(Path dbFile, Path libDir) throws Exception {
        Driver d=ensureDriver(libDir);
        Properties props=new Properties();
        Connection c=d.connect("jdbc:sqlite:"+dbFile.toAbsolutePath(), props);
        if(c==null)throw new SQLException("SQLite JDBC driver refused jdbc:sqlite URL");
        return c;
    }
    static boolean available(Path libDir) {
        try {
            ensureDriver(libDir);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    private static Driver ensureDriver(Path libDir) throws Exception {
        Driver ready=DRIVER.get();
        if(ready!=null)return ready;
        synchronized(SqliteDriverBootstrap.class) {
            ready=DRIVER.get();
            if(ready!=null)return ready;
            Files.createDirectories(libDir);
            Artifact nativeJar=nativeArtifact();
            Path classes=install(libDir, CLASSES), natives=install(libDir, nativeJar);
            URLClassLoader cl=new URLClassLoader(new URL[] {
                classes.toUri().toURL(), natives.toUri().toURL()
            },SqliteDriverBootstrap.class.getClassLoader());
            Class<?> jdbc=Class.forName("org.sqlite.JDBC", true, cl);
            Object o=jdbc.getDeclaredConstructor().newInstance();
            if(!(o instanceof Driver d))throw new SQLException("org.sqlite.JDBC does not implement java.sql.Driver");
            loader=cl;
            DRIVER.set(d);
            
            return d;
        }
    }
    private static Artifact nativeArtifact() {
        String os=System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if(os.contains("win"))return new Artifact("sqlite-jdbc-"+VERSION+"-natives-windows.jar", "05c622ecd7337d96f7ccbd0599fee00cf62af81364fd188a848eb3469f4e17a2");
        if(os.contains("mac")||os.contains("darwin"))return new Artifact("sqlite-jdbc-"+VERSION+"-natives-mac.jar",
            "803ecf522fce27b320036ea4c84ac743ec42523e05aaa42d7848f4520599e1ee");
        if(os.contains("freebsd"))return new Artifact("sqlite-jdbc-"+VERSION+"-natives-freebsd.jar", "83e4a6038e5b737b55640d9a97373a73903c00f3fc9f50bc9d4cd48c8e03011b");
        if(os.contains("linux"))return new Artifact("sqlite-jdbc-"+VERSION+"-natives-linux.jar", "0f0b985595ac68cafb48d462d8a0a039ae943767baec9c04704cf7fbc8dc0e00");
        return new Artifact("sqlite-jdbc-"+VERSION+"-natives-all.jar", "c89f8055711094e46f3f4f8573016438a093c7b51c91b8e0d63b0638e502a739");
    }
    private static Path install(Path dir, Artifact a) throws Exception {
        Path file=dir.resolve(a.name());
        if(Files.isRegularFile(file)&&a.sha256().equalsIgnoreCase(sha256(file)))return file;
        Files.deleteIfExists(file);
        Path part=file.resolveSibling(file.getFileName()+".part");
        Files.deleteIfExists(part);
        IOException last=null;
        for(String url:List.of(BASE+a.name(), "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/"+VERSION+"/"+a.name())) {
            try {
                
                HttpClient hc=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpRequest req=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).header("User-Agent", "TierLookup/SQLiteBootstrap").GET().build();
                HttpResponse<Path> r=hc.send(req, HttpResponse.BodyHandlers.ofFile(part));
                if(r.statusCode()/100!=2)throw new IOException("HTTP "+r.statusCode());
                String got=sha256(part);
                if(!a.sha256().equalsIgnoreCase(got))throw new IOException("SHA-256 mismatch expected="+a.sha256()+" got="+got);
                try {
                    Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
                }
                return file;
            } catch (IOException e) {
                last=e;
                Files.deleteIfExists(part);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        throw new IOException("Cannot install SQLite JDBC component "+a.name(), last);
    }
    private static String sha256(Path p)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(p)) {
            byte[] b=new byte[1<<16];
            for(int n; (n=in.read(b))>0;)md.update(b, 0, n);
        }
        StringBuilder s=new StringBuilder();
        for(byte x:md.digest())s.append(String.format("%02x", x));
        return s.toString();
    }
}

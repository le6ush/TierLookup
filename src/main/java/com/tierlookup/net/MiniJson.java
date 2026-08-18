package com.tierlookup.net;

import java.util.*;

/** Tiny dependency-free JSON parser for API responses/config. */
public final class MiniJson {
    private MiniJson() {
    }
    public static Object parse(String s) {
        return new Parser(s).parse();
    }
    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return '"' + escape(s) + '"';
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            boolean first=true;
            for (var e:m.entrySet()) {
                if(!first)b.append(',');
                first=false;
                b.append(stringify(String.valueOf(e.getKey()))).append(':').append(stringify(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (value instanceof Iterable<?> it) {
            StringBuilder b = new StringBuilder("[");
            boolean first=true;
            for(Object v:it) {
                if(!first)b.append(',');
                first=false;
                b.append(stringify(v));
            }
            return b.append(']').toString();
        }
        return stringify(String.valueOf(value));
    }
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    private static final class Parser {
        private final String s;
        private int i;
        Parser(String s) {
            this.s=s==null?"":s;
        }
        Object parse() {
            ws();
            Object v=value();
            ws();
            return v;
        }
        Object value() {
            ws();
            if(i>=s.length()) throw err("unexpected end");
            char c=s.charAt(i);
            if(c=='{')return object();
            if(c=='[')return array();
            if(c=='\"')return string();
            if(c=='t'&&eat("true"))return true;
            if(c=='f'&&eat("false"))return false;
            if(c=='n'&&eat("null"))return null;
            if(c=='-'||Character.isDigit(c))return number();
            throw err("unexpected '"+c+"'");
        }
        Map<String, Object> object() {
            i++;
            LinkedHashMap<String, Object> m=new LinkedHashMap<>();
            ws();
            if(peek('}')) {
                i++;
                return m;
            } while (true) {
                ws();
                String k=string();
                ws();
                need(':');
                Object v=value();
                m.put(k, v);
                ws();
                if(peek('}')) {
                    i++;
                    break;
                }
                need(',');
            }
            return m;
        }
        List<Object> array() {
            i++;
            ArrayList<Object> a=new ArrayList<>();
            ws();
            if(peek(']')) {
                i++;
                return a;
            } while (true) {
                a.add(value());
                ws();
                if(peek(']')) {
                    i++;
                    break;
                }
                need(',');
            }
            return a;
        }
        String string() {
            need('"');
            StringBuilder b=new StringBuilder();
            while(i<s.length()) {
                char c=s.charAt(i++);
                if(c=='"')return b.toString();
                if(c=='\\') {
                    if(i>=s.length())throw err("bad escape");
                    char e=s.charAt(i++);
                    switch(e) {
                        case '"', '\\', '/'->b.append(e);
                        case 'b'->b.append('\b');
                        case 'f'->b.append('\f');
                        case 'n'->b.append('\n');
                        case 'r'->b.append('\r');
                        case 't'->b.append('\t');
                        case 'u'-> {
                            if(i+4>s.length())throw err("bad unicode");
                            b.append((char)Integer.parseInt(s.substring(i, i+4), 16));
                            i+=4;
                        }
                        default->throw err("bad escape");
                    }
                } else b.append(c);
            }
            throw err("unterminated string");
        }
        Number number() {
            int st=i;
            if(peek('-'))i++;
            while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;
            if(peek('.')) {
                i++;
                while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;
            }
            if(i<s.length()&&(s.charAt(i)=='e'||s.charAt(i)=='E')) {
                i++;
                if(i<s.length()&&(s.charAt(i)=='+'||s.charAt(i)=='-'))i++;
                while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;
            }
            String n=s.substring(st, i);
            return n.contains(".")||n.contains("e")||n.contains("E")?Double.parseDouble(n):Long.parseLong(n);
        }
        boolean eat(String x) {
            if(s.startsWith(x, i)) {
                i+=x.length();
                return true;
            }
            return false;
        }
        void ws() {
            while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;
        }
        boolean peek(char c) {
            return i<s.length()&&s.charAt(i)==c;
        }
        void need(char c) {
            ws();
            if(!peek(c))throw err("expected "+c);
            i++;
        }
        RuntimeException err(String x) {
            return new IllegalArgumentException(x+" at "+i);
        }
    }
}

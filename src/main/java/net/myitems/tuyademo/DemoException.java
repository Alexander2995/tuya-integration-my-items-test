package net.myitems.tuyademo;

public class DemoException extends RuntimeException {
    public final String code;
    public DemoException(String code, String safeMessage) { super(safeMessage); this.code = code; }
}

package net.myitems.tuyademo;

import java.util.Map;
import org.apache.pulsar.client.api.*;

/** Tuya's auth1 command authentication; not the REST access token. */
final class TuyaQueueAuthentication implements Authentication {
    private final String id, secret;
    TuyaQueueAuthentication(String id, String secret) { this.id = id; this.secret = secret; }
    public String getAuthMethodName() { return "auth1"; }
    public AuthenticationDataProvider getAuthData() {
        return new AuthenticationDataProvider() {
            public boolean hasDataFromCommand() { return true; }
            public boolean hasDataForHttp() { return false; }
            public String getCommandData() { return TuyaQueueCrypto.authentication(id, secret); }
        };
    }
    public void configure(Map<String,String> ignored) {}
    public void start() {}
    public void close() {}
}

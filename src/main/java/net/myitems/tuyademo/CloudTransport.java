package net.myitems.tuyademo;

import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import org.springframework.stereotype.Component;

@FunctionalInterface
public interface CloudTransport {
    record Reply(int status, String body) {}
    Reply send(HttpRequest request) throws IOException, InterruptedException;
}

@Component
class JdkCloudTransport implements CloudTransport {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(7))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public Reply send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        return new Reply(r.statusCode(), r.body());
    }
}

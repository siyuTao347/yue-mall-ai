package com.example.agent.customer;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** JDK async transport cancels its HTTP future on interruption and times out response bodies too. */
final class CustomerHttpRequestFactory implements ClientHttpRequestFactory {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final int maxMillis;
    CustomerHttpRequestFactory(int maxMillis) { this.maxMillis=maxMillis; }
    @Override public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        var factory=new JdkClientHttpRequestFactory(HTTP);
        factory.setReadTimeout(CustomerBudget.remaining(maxMillis));
        return factory.createRequest(uri,method);
    }
}

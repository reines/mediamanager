package com.jamierf.mediamanager.parsing;

import com.jamierf.mediamanager.io.HttpParser;
import com.yammer.dropwizard.client.HttpClientFactory;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.protocol.HttpContext;

import java.net.URI;
import java.util.Set;

public abstract class FeedParser<T extends FeedItem> extends HttpParser<T> {

    public FeedParser(HttpClientFactory clientFactory, String url) {
        super(clientFactory, url);
    }

    public Set<T> parse() throws Exception {
        final HttpClient client = this.buildClient();
        final HttpContext context = this.buildContext();
        final HttpUriRequest request = this.buildRequest();

        return this.parse(client, context, request);
    }
}

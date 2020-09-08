package com.pusher.client;

import com.pusher.client.channel.impl.AuthResponseData;

import java.util.List;

public abstract class AuthRequestHandler {
    public abstract Authorizer getAuthorizer();

    public abstract AuthResponseData authRequest(String socketId, List<String> channels, Boolean appendToken);

    public abstract void warningLog(Throwable e);
}

package com.pusher.client;

public class SecretboxOpenerRemovedException extends RuntimeException {

    public SecretboxOpenerRemovedException() {
        super();
    }

    public SecretboxOpenerRemovedException(final String msg) {
        super(msg);
    }

    public SecretboxOpenerRemovedException(final Throwable cause) {
        super(cause);
    }

    public SecretboxOpenerRemovedException(final String msg, final Throwable cause) {
        super(msg, cause);
    }
}

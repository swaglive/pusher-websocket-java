package com.pusher.client.channel.impl.message;

import com.google.gson.annotations.SerializedName;

public class AuthResponse {
    private String auth;
    @SerializedName("channel_data")
    private String channelData;

    @SerializedName("shared_secret")
    private String sharedSecret;

    public String getAuth() {
        return auth;
    }

    public String getChannelData() {
        return channelData;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }
}

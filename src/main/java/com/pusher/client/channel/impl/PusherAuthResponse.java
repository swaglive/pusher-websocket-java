package com.pusher.client.channel.impl;

import com.google.gson.annotations.SerializedName;

public class PusherAuthResponse {

    @SerializedName("auth")
    String auth;

    @SerializedName("channel_data")
    String channelData;

    @SerializedName("shared_secret")
    String sharedSecret;

    @Override
    public String toString() {
        return "PusherAuthResponse{" +
                "auth='" + auth + '\'' +
                ", channelData='" + channelData + '\'' +
                ", sharedSecret='" + sharedSecret + '\'' +
                '}';
    }
}


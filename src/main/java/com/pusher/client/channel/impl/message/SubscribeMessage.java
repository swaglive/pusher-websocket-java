package com.pusher.client.channel.impl.message;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class SubscribeMessage {
    private String event = "pusher:subscribe";
    private TreeMap<String, String> data = new TreeMap<>();

    // You can call this subscribe message for channels that do not require authentication
    // e.g. public channels.
    public SubscribeMessage(String channelName) {
        data.put("channel", channelName);
    }

    // You will want to call this subscribe message for channels that require authentication
    // e.g. private and presence channels.
    public SubscribeMessage(String channelName, String auth, String channelData) {
        data.put("channel", channelName);
        data.put("auth", auth);
        if (channelData != null) {
            data.put("channel_data", channelData);
        }

    }
}

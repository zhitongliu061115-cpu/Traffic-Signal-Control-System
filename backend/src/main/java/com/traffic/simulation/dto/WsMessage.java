package com.traffic.simulation.dto;

public record WsMessage<T>(
        String v,
        String type,
        String sid,
        long seq,
        double simTime,
        String sentAt,
        T data
) {
}

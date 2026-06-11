package com.borderra.synapse.api;

import org.bukkit.Bukkit;

import java.util.Optional;

public final class SynapseProvider {
    private SynapseProvider() {
    }

    public static Optional<SynapseApi> get() {
        var registration = Bukkit.getServicesManager().getRegistration(SynapseApi.class);
        if (registration == null) {
            return Optional.empty();
        }
        return Optional.of(registration.getProvider());
    }
}

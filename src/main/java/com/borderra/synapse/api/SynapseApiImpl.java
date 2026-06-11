package com.borderra.synapse.api;

public final class SynapseApiImpl implements SynapseApi {
    private final LinkApi linkApi;
    private final DiscordBotApi discordBotApi;

    public SynapseApiImpl(LinkApi linkApi, DiscordBotApi discordBotApi) {
        this.linkApi = linkApi;
        this.discordBotApi = discordBotApi;
    }

    @Override
    public LinkApi links() {
        return linkApi;
    }

    @Override
    public DiscordBotApi discord() {
        return discordBotApi;
    }
}

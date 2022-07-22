package cc.nium.sni.config;

import java.util.ArrayList;

public final class Config {

    private int headBufferSize = 8 * 1024;
    private int forwarderBufferSize = 8 * 1024;
    private ArrayList<ServerConfig> servers;

    public int getHeadBufferSize() {
        return headBufferSize;
    }

    public void setHeadBufferSize(int headBufferSize) {
        this.headBufferSize = headBufferSize;
    }

    public int getForwarderBufferSize() {
        return forwarderBufferSize;
    }

    public void setForwarderBufferSize(int forwarderBufferSize) {
        this.forwarderBufferSize = forwarderBufferSize;
    }

    public ArrayList<ServerConfig> getServers() {
        return servers;
    }

    public void setServers(ArrayList<ServerConfig> servers) {
        this.servers = servers;
    }
}

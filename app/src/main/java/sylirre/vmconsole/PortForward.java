package sylirre.vmconsole;

import java.util.ArrayList;

public final class PortForward {

    public final int hostPort;
    public final int guestPort;
    public final String label;

    public PortForward(String label, int hostPort, int guestPort) {
        this.label = label == null ? "Custom" : label;
        this.hostPort = hostPort;
        this.guestPort = guestPort;
    }

    public String toDisplayString() {
        return label + ": " + hostPort + " -> " + guestPort;
    }

    public static ArrayList<PortForward> parseUserConfig(String rawConfig) {
        ArrayList<PortForward> forwards = new ArrayList<>();
        if (rawConfig == null || rawConfig.trim().isEmpty()) {
            return forwards;
        }

        String[] parts = rawConfig.split("[,;\\n]+");
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }

            try {
                int hostPort;
                int guestPort;

                if (token.contains(":")) {
                    String[] mapping = token.split(":", 2);
                    hostPort = Integer.parseInt(mapping[0].trim());
                    guestPort = Integer.parseInt(mapping[1].trim());
                } else {
                    hostPort = Integer.parseInt(token);
                    guestPort = hostPort;
                }

                if (isValidPort(hostPort) && isValidPort(guestPort)) {
                    forwards.add(new PortForward("Custom", hostPort, guestPort));
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return forwards;
    }

    private static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }
}

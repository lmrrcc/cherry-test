package mini.discord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;

interface StateStore {
    Map<String, ObjectNode> users();

    Map<String, DiscordState.Application> applications();

    Map<String, DiscordState.Guild> guilds();

    Map<String, ObjectNode> channels();

    Map<String, DiscordState.Invite> invites();

    Map<String, List<ObjectNode>> messages();

    Set<String> syncedChannels();

    List<ObjectNode> events();

    static StateStore memory() {
        return new Memory(
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashSet<>(),
                new ArrayList<>());
    }

    record Memory(
            Map<String, ObjectNode> users,
            Map<String, DiscordState.Application> applications,
            Map<String, DiscordState.Guild> guilds,
            Map<String, ObjectNode> channels,
            Map<String, DiscordState.Invite> invites,
            Map<String, List<ObjectNode>> messages,
            Set<String> syncedChannels,
            List<ObjectNode> events)
            implements StateStore {}
}

package top.vulpine.catalog.paper.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.process.SenderResolver;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("MainCommand")
class MainCommandTest {

    /**
     * Registration is where Lamp checks the shape of every command, and none of what it checks is
     * visible to the compiler: optional parameters and flags must each come last, and so must a
     * parameter that may contain spaces. Getting that wrong throws while the server is starting,
     * which means the plugin does not load at all.
     *
     * <p>Building the tree needs no platform, so the whole command surface can be proven to
     * register here rather than on someone's server.</p>
     */
    @Test
    @DisplayName("every command has a shape Lamp will accept")
    void registers() {

        // Stands in for the Bukkit platform, which is the only thing missing outside a server.
        SenderResolver<CommandActor> sender = new SenderResolver<>() {

            @Override
            public boolean isSenderType(revxrsal.commands.command.CommandParameter parameter) {
                return CommandSender.class.isAssignableFrom(parameter.type());
            }

            @Override
            public Object getSender(Class<?> type, CommandActor actor,
                                    revxrsal.commands.command.ExecutableCommand<CommandActor> command) {
                throw new UnsupportedOperationException("nothing is executed here");
            }
        };

        Lamp<CommandActor> lamp = Lamp.<CommandActor>builder()
                .permissionFactory((annotations, l) -> actor -> true)
                .senderResolver(sender)
                .build();

        // Never dereferenced: registration reflects over the class, it does not call anything.
        assertDoesNotThrow(() -> lamp.register(new MainCommand(null)));
    }

}

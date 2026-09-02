package top.vulpine.catalog.modrinth.model;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * One person on a project's team.
 *
 * <p>Modrinth keeps authorship on the team rather than on the project, so a project alone cannot
 * say who made it — the members have to be asked for separately.</p>
 */
@Getter
@Accessors(fluent = true)
public final class TeamMember {

    private String teamId;
    private User user;
    private String role;
    private boolean accepted;
    private int ordering;

    /**
     * The name to show, preferring the display name the person chose over their handle.
     *
     * @return the name, or null if this member has no user attached
     */
    public String displayName() {

        if (user == null) {
            return null;
        }

        return user.name != null && !user.name.isBlank() ? user.name : user.username;
    }

    /**
     * Picks the one member worth putting on a page.
     *
     * <p>The owner if there is one, otherwise whoever the team lists first. Modrinth's own ordering
     * is the closest thing to an author credit that exists.</p>
     *
     * @param members the team, as returned by the API
     * @return the name to credit, or null if the team is empty
     */
    public static String credit(List<TeamMember> members) {

        if (members == null || members.isEmpty()) {
            return null;
        }

        TeamMember best = null;

        for (TeamMember member : members) {

            if ("Owner".equalsIgnoreCase(member.role)) {
                return member.displayName();
            }

            if (best == null || member.ordering < best.ordering) {
                best = member;
            }
        }

        return best == null ? null : best.displayName();
    }

    @Getter
    @Accessors(fluent = true)
    public static final class User {

        private String id;
        private String username;
        private String name;

    }

}

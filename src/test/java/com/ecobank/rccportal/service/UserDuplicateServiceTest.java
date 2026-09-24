package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserDuplicateServiceTest {

    private static User user(long id, String username, String name, String team, String email) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setName(name);
        u.setActivity(team);
        u.setEmail(email);
        u.setAccountEnabled(true);
        return u;
    }

    @Test
    void threeAccountsOfTheSameAgentFormOneGroupAndKeepTheRealTeamAccount() {
        List<User> users = List.of(
                user(1, "mmoke", "MOKE Marie Carmella", "INBOUND", null),
                user(2, "moke.marie.carmella.2", "MOKE Marie Carmella (2°)", "Rcc", null),
                user(3, "moke.carmela", "MOKE CARMELA", "Rcc", null),
                user(4, "kjean", "Kouassi Jean", "OUTBOUND", null));
        List<UserDuplicateService.Group> groups = UserDuplicateService.groups(users);
        assertEquals(1, groups.size());
        UserDuplicateService.Group g = groups.get(0);
        assertEquals(3, g.members().size());
        assertTrue(g.members().get(0).main());
        assertEquals("mmoke", g.members().get(0).username());
    }

    @Test
    void sameEmailIsADuplicateEvenWithDifferentNames() {
        List<User> users = List.of(
                user(1, "a.brou", "Brou Aya", "INBOUND", "aya.brou@ecobank.com"),
                user(2, "ABROU", "Aya B.", null, "AYA.BROU@ecobank.com"));
        List<UserDuplicateService.Group> groups = UserDuplicateService.groups(users);
        assertEquals(1, groups.size());
        assertEquals("même e-mail", groups.get(0).reason());
    }

    @Test
    void completeMainDropsImportSuffixAndFillsMissingData() {
        User main = user(1, "mmoke", "MOKE Carmella", null, null);
        User dup = user(2, "moke.marie.carmella.2", "MOKE Marie Carmella (2°)", "INBOUND", "mmoke@ecobank.com");
        UserDuplicateService.completeMain(main, dup);
        assertEquals("MOKE Marie Carmella", main.getName());
        assertEquals("INBOUND", main.getActivity());
        assertEquals("mmoke@ecobank.com", main.getEmail());
    }
}

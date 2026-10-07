package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.repository.UserRepository;
import com.ecobank.rccportal.util.Filiale;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/**
 * Restreint une liste de résultats (ventes, rendez-vous, plannings, pointages, évaluations…) à la filiale active
 * (RCC ECI / RCC ETG, voir Filiale) d'après le compte concerné. Sans filiale active, la liste est rendue telle quelle.
 */
@Service
public class FilialeScope {

    private final UserRepository users;

    public FilialeScope(UserRepository users) {
        this.users = users;
    }

    private record Index(Set<Long> ids, Set<String> usernames) {}

    private Index index() {
        Set<Long> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (User u : users.findAll()) {
            if (!Filiale.matches(u.getAffiliateBranch())) continue;
            ids.add(u.getId());
            if (u.getUsername() != null) names.add(u.getUsername().toLowerCase(Locale.ROOT));
        }
        return new Index(ids, names);
    }

    public <T> List<T> byUserId(List<T> list, Function<T, Long> userId) {
        if (Filiale.current() == null || list == null) return list;
        Set<Long> ids = index().ids();
        return list.stream().filter(x -> userId.apply(x) == null || ids.contains(userId.apply(x))).toList();
    }

    public <T> List<T> byUsername(List<T> list, Function<T, String> username) {
        if (Filiale.current() == null || list == null) return list;
        Set<String> names = index().usernames();
        return list.stream().filter(x -> {
            String n = username.apply(x);
            return n == null || names.contains(n.toLowerCase(Locale.ROOT));
        }).toList();
    }
}

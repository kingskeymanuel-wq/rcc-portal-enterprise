package com.ecobank.rccportal.service;

import com.ecobank.rccportal.model.MailTemplate;
import com.ecobank.rccportal.model.User;
import com.ecobank.rccportal.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailTemplateVisibilityTest {

    private static MailTemplate template(boolean system, String author) {
        MailTemplate t = new MailTemplate();
        t.setIsSystemTemplate(system);
        if (author != null) {
            User u = new User();
            u.setUsername(author);
            t.setCreatedBy(u);
        }
        return t;
    }

    private static AuthenticatedUser user(String username) {
        return new AuthenticatedUser(username, "AGENT", "EMAIL", username);
    }

    @Test
    void personalTemplateIsOnlyVisibleToItsAuthor() {
        MailTemplate mine = template(false, "iyoro");
        assertTrue(MailTemplateService.visibleTo(mine, user("IYORO")));
        assertFalse(MailTemplateService.visibleTo(mine, user("itoure")));
        assertFalse(MailTemplateService.visibleTo(mine, null));
    }

    @Test
    void systemTemplatesStayShared() {
        assertTrue(MailTemplateService.visibleTo(template(true, "qa.user"), user("itoure")));
        assertTrue(MailTemplateService.visibleTo(template(false, null), user("itoure")));
    }
}

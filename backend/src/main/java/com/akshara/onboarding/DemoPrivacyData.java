package com.akshara.onboarding;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.audit.AuditService.Actor;
import com.akshara.privacy.ConsentService;
import com.akshara.privacy.DataRequestService;
import com.akshara.privacy.PrivacyForms.AcceptForm;
import com.akshara.privacy.PrivacyForms.ConsentChoice;
import com.akshara.privacy.PrivacyForms.NoticeForm;
import com.akshara.privacy.PrivacyForms.OfficerForm;
import com.akshara.privacy.PrivacyForms.RequestForm;
import com.akshara.privacy.PrivacyNoticeService;
import com.akshara.privacy.PrivacyViews.ChildConsent;
import com.akshara.privacy.PrivacyViews.NoticeAdmin;
import com.akshara.privacy.PrivacyViews.ParentPrivacy;
import com.akshara.privacy.RequestSubject;
import com.akshara.privacy.RequestType;

/**
 * The demo school's data protection: the principal as grievance officer, version 1 of the privacy notice from the
 * default template, the demo parent's consent for Arjun (photos and WhatsApp) and Diya (WhatsApp only), and one open
 * access request about Arjun waiting in the queue. Runs inside the demo school's seeding.
 */
@Component
class DemoPrivacyData {

    static final String OFFICER_PHONE = "040 2345 6789";
    static final String REQUEST_DETAILS = "Please send me a copy of the information the school keeps about Arjun.";

    private final PrivacyNoticeService notices;
    private final ConsentService consents;
    private final DataRequestService requests;

    DemoPrivacyData(PrivacyNoticeService notices, ConsentService consents, DataRequestService requests) {
        this.notices = notices;
        this.consents = consents;
        this.requests = requests;
    }

    /** Returns the published notice version. */
    int seed(Map<String, UUID> userIds) {
        String principalEmail = "principal" + DemoDataSeeder.DEMO_DOMAIN;
        Actor principal = new Actor(userIds.get(principalEmail), "Lakshmi Iyer");
        notices.updateOfficer(new OfficerForm("Lakshmi Iyer", principalEmail, OFFICER_PHONE), principal);
        NoticeAdmin draft = notices.admin();
        int version = notices.publish(new NoticeForm(draft.draftEn(), draft.draftHi(), null), principal).version();

        UUID parentId = userIds.get("parent" + DemoDataSeeder.DEMO_DOMAIN);
        Actor parent = new Actor(parentId, "Anitha Sharma");
        ParentPrivacy before = consents.forParent(parentId);
        List<ConsentChoice> choices = before.children().stream()
                .map(ChildConsent::studentId)
                .map(id -> new ConsentChoice(id, isArjun(before, id), true))
                .toList();
        consents.accept(parentId, new AcceptForm(version, true, choices), parent);

        UUID arjun = before.children().stream().filter(c -> "Arjun Sharma".equals(c.fullName()))
                .map(ChildConsent::studentId).findFirst().orElseThrow();
        requests.submit(parentId, new RequestForm(RequestType.ACCESS, RequestSubject.CHILD, arjun, REQUEST_DETAILS),
                parent);
        return version;
    }

    private static boolean isArjun(ParentPrivacy privacy, UUID studentId) {
        return privacy.children().stream()
                .anyMatch(c -> c.studentId().equals(studentId) && "Arjun Sharma".equals(c.fullName()));
    }
}

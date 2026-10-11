package com.akshara.chat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService.Actor;
import com.akshara.chat.ChatAccess.Me;
import com.akshara.chat.ChatPeople.Person;
import com.akshara.chat.PtmForms.EventForm;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Writes example conversations and meetings for the demo school (onboarding). Conversations are written as they would
 * have been over the past days (with those times), without notifications; meetings and bookings go through the same
 * checks as the API. Not used by the API.
 */
@Service
@Transactional
public class ChatSeeder {

    /** One message of an example conversation: who wrote it, when, and whether the other side has seen it. */
    public record SeedMessage(Side side, String body, Instant at, boolean read) {
    }

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatPeople people;
    private final PtmService ptm;
    private final PtmBookingService bookings;
    private final PtmSlotRepository slots;

    ChatSeeder(ConversationRepository conversations, ChatMessageRepository messages, ChatPeople people,
            PtmService ptm, PtmBookingService bookings, PtmSlotRepository slots) {
        this.conversations = conversations;
        this.messages = messages;
        this.people = people;
        this.ptm = ptm;
        this.bookings = bookings;
        this.slots = slots;
    }

    /** A conversation between a parent and a member of staff about a student, with its messages in order. */
    public UUID conversation(UUID studentId, UUID parentUserId, UUID staffUserId, List<SeedMessage> list) {
        TenantContext.require();
        if (list.isEmpty()) {
            throw new IllegalArgumentException("An example conversation needs a message");
        }
        Map<UUID, Person> persons = people.people(List.of(parentUserId, staffUserId));
        if (persons.size() != 2 || !people.isParentOf(parentUserId, studentId)) {
            throw new IllegalArgumentException("The parent, the member of staff and the student must exist and be "
                    + "linked");
        }
        SeedMessage first = list.get(0);
        Conversation c = conversations.saveAndFlush(new Conversation(studentId, parentUserId, staffUserId,
                first.side(), first.at()));
        for (SeedMessage m : list) {
            UUID sender = m.side() == Side.PARENT ? parentUserId : staffUserId;
            ChatMessage message = new ChatMessage(c.getId(), sender, m.side(), persons.get(sender).name(), m.body(), 0,
                    m.at());
            if (m.read()) {
                message.markRead(m.at().plusSeconds(600));
            }
            messages.save(message);
            c.messageAdded(m.at());
        }
        messages.flush();
        conversations.saveAndFlush(c);
        return c.getId();
    }

    /** Sets up a meeting as leadership would; returns its id. */
    public UUID meeting(EventForm form, Actor actor) {
        return ptm.create(form, actor, Instant.now(), ChatRules.today()).id();
    }

    /** Switches PTM reminders on or off for the school. */
    public void ptmReminders(boolean enabled, Actor actor) {
        ptm.updatePtmSettings(enabled, actor, Instant.now(), ChatRules.today());
    }

    /** A teacher's slots at a meeting, by start time. */
    public Map<LocalTime, UUID> slots(UUID eventId, UUID teacherUserId) {
        TenantContext.require();
        Map<LocalTime, UUID> result = new LinkedHashMap<>();
        slots.ofEvent(eventId).stream().filter(s -> s.getTeacherUserId().equals(teacherUserId))
                .forEach(s -> result.put(s.getStartsAt(), s.getId()));
        return result;
    }

    /** A parent books a slot for their child, as in the app; returns the booking's id. */
    public UUID bookAsParent(UUID parentUserId, String parentName, UUID slotId, UUID studentId) {
        try {
            return bookings.bookForChild(new Me(parentUserId, parentName, Side.PARENT), slotId, studentId,
                    Instant.now(), ChatRules.today()).id();
        } catch (ApiException e) {
            throw new IllegalStateException("Could not book the example slot: " + e.getMessage(), e);
        }
    }

    /** The school books a slot for a family; returns the booking's id. */
    public UUID bookForFamily(UUID eventId, UUID slotId, UUID studentId, Actor actor) {
        return bookings.bookForFamily(eventId, slotId, studentId, actor, Instant.now(), ChatRules.today()).id();
    }
}

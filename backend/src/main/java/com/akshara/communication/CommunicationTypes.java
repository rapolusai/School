package com.akshara.communication;

/** Small value types of the circulars and calendar API. */
public final class CommunicationTypes {

    private CommunicationTypes() {
    }

    /** What a circular is about; URGENT circulars are pinned at the top of notice boards for a week. */
    public enum Category {
        GENERAL, ACADEMIC, EVENT, HOLIDAY, FEES, URGENT
    }

    /**
     * DRAFT, then PENDING_APPROVAL (when the sender needs approval), then SCHEDULED or SENT; a sent circular can be
     * WITHDRAWN. A rejected circular goes back to DRAFT with the reviewer's note.
     */
    public enum Status {
        DRAFT, PENDING_APPROVAL, SCHEDULED, SENT, WITHDRAWN
    }

    /** Who wrote a circular: a person, or the calendar (a reminder of an entry). */
    public enum Source {
        STAFF, CALENDAR
    }

    /** Ways a circular reaches people besides the in-app notice board, which every circular uses. */
    public enum NoticeChannel {
        SMS, WHATSAPP, EMAIL
    }

    public enum ReviewOutcome {
        APPROVED, REJECTED
    }

    /** How a recipient with a sign-in relates to the school. */
    public enum RecipientKind {
        STAFF, PARENT, STUDENT
    }

    public enum EntryKind {
        HOLIDAY, EVENT, EXAM, PTM, OTHER
    }

    /** Who sees a calendar entry. Staff see every entry; families see whole-school entries and their classes'. */
    public enum CalendarAudience {
        SCHOOL, CLASSES, STAFF
    }

    /** Channels a calendar reminder may use besides the notice board. */
    public enum ReminderChannel {
        SMS, WHATSAPP
    }

    /** How the acknowledgement of a public admissions enquiry is sent. */
    public enum AckChannel {
        SMS, WHATSAPP_SMS
    }
}

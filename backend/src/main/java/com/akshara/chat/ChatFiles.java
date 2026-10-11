package com.akshara.chat;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.files.FileAccessPolicy;
import com.akshara.files.StoredFileInfo;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;

/**
 * Who may download a message attachment: the two people in the conversation while the message is not deleted (staff
 * with chat.use, the parent with child.view), and the Principal's oversight (chat.oversee), who also sees what was
 * deleted. Reading the conversation in oversight is what the audit log records.
 */
@Component
class ChatFiles implements FileAccessPolicy {

    private final ChatMessageRepository messages;
    private final ConversationRepository conversations;

    ChatFiles(ChatMessageRepository messages, ConversationRepository conversations) {
        this.messages = messages;
        this.conversations = conversations;
    }

    @Override
    public String ownerModule() {
        return ConversationService.MODULE;
    }

    @Override
    public boolean canRead(StoredFileInfo file) {
        Optional<UUID> user = CurrentUser.id();
        if (user.isEmpty() || !ConversationService.MESSAGE_FILE.equals(file.ownerType())) {
            return false;
        }
        ChatMessage m = messages.findById(file.ownerId()).orElse(null);
        if (m == null || m.isErased()) {
            return false;
        }
        Conversation c = conversations.findById(m.getConversationId()).orElse(null);
        if (c == null || c.isErased()) {
            return false;
        }
        Set<String> permissions = ChatAccess.permissions();
        if (permissions.contains(Permissions.CHAT_OVERSEE)) {
            return true;
        }
        if (m.isDeleted()) {
            return false;
        }
        if (user.get().equals(c.getStaffUserId())) {
            return permissions.contains(Permissions.CHAT_USE);
        }
        return user.get().equals(c.getParentUserId()) && permissions.contains(Permissions.CHILD_VIEW);
    }
}

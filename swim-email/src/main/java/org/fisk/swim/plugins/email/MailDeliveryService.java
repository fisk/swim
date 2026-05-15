package org.fisk.swim.plugins.email;

import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailSendResult;

interface MailDeliveryService {
    MailSendResult send(EmailAccountConfig account, MailDraft draft) throws Exception;
}

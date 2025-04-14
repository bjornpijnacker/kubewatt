package com.bjornp.kubewatt.utils.datastorage;

import com.bjornp.kubewatt.KubeWatt;
import com.bjornp.kubewatt.utils.config.Config;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.mail.EmailAttachment;
import org.apache.commons.mail.MultiPartEmail;

import java.io.IOException;

/**
 * The EmailDataStorageProvider is a proxy around FileDataStorageProvider that e-mails the files to the specified recipient(s) after the provider is closed.
 */
@Slf4j
public class EmailDataStorageProvider implements DataStorageProvider {
    private final String name;
    private final String id;
    private final FileDataStorageProvider inner;

    protected EmailDataStorageProvider(String name, String id, String... dataLabels) throws IOException {
        if (Config.get().dataStorage().email() == null) throw new RuntimeException("Cannot init EmailDataStorage without email config");

        this.name = name;
        this.id = id;
        this.inner = new FileDataStorageProvider(name, id, dataLabels);
    }

    @Override
    public void addData(String node, double... data) throws IOException {
        inner.addData(node, data);
    }

    @Override
    public void close() throws Exception {
        log.info("EmailDataStorageProvider '{} {}' is closed; sending e-mail!", name, id);
        inner.close();

        var files = inner.getStorageProviders().values()
                .stream().map(SingleNodeFileDataStorageProvider::getFilename)
                .toList();

        try {
            var email = new MultiPartEmail();
            email.setHostName(Config.get().dataStorage().email().hostname());
            if (Config.get().dataStorage().email().useSsl()) {
                email.setSslSmtpPort(String.valueOf(Config.get().dataStorage().email().port()));
                email.setSSLOnConnect(true);
            } else {
                email.setSmtpPort(Config.get().dataStorage().email().port());
                email.setSSLOnConnect(false);
            }
            email.setAuthentication(Config.get().dataStorage().email().username(), Config.get().dataStorage().email().password());
            email.setFrom(Config.get().dataStorage().email().from());
            email.setSubject("%d %s %s".formatted(KubeWatt.start.getEpochSecond(), name, id));
            email.setMsg("KubeWatt data is ready:\n%d %s %s".formatted(KubeWatt.start.getEpochSecond(), name, id));
            email.addTo(Config.get().dataStorage().email().recipient());

            for (var file : files) {
                var att = new EmailAttachment();
                att.setPath(file);
                att.setDisposition(EmailAttachment.ATTACHMENT);
                att.setName(file);
                email.attach(att);
            }

            email.send();
        } catch (Throwable t) {  // catch ERRORS also
            log.error("Unable to send e-mail. Not considering this fatal!", t);
        }
    }
}

package org.example.proect.lavka.utils;

import org.example.proect.lavka.wp_object.ImageAttachment;
import org.springframework.stereotype.Component;

@Component
public class ImageAttachmentFactory {
    private final AttachmentNaming naming;

    public ImageAttachmentFactory(AttachmentNaming naming) {
        this.naming = naming;
    }

    public ImageAttachment create(ImageAttachment.Builder b) {
        return new ImageAttachment(b, naming);
    }
    public AttachmentNaming getNaming() {
        return naming;
    }
}
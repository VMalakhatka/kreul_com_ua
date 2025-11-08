package org.example.proect.lavka.utils;


import org.springframework.stereotype.Component;

@Component
public class ImageAttachmentFactory {
    private final AttachmentNaming naming;
    public ImageAttachmentFactory(AttachmentNaming naming){ this.naming = naming; }
    public AttachmentNaming getNaming(){ return naming; }
}
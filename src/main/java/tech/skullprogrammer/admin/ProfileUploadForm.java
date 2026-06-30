package tech.skullprogrammer.admin;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

public class ProfileUploadForm {

    @RestForm("profile")
    public FileUpload profile;
}

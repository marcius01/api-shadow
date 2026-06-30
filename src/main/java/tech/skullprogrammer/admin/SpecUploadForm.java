package tech.skullprogrammer.admin;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

public class SpecUploadForm {

    @RestForm("name")
    public String name;

    @RestForm("file")
    public FileUpload file;

    @RestForm("profile")
    public FileUpload profile;

    @RestForm("semanticMode")
    public boolean semanticMode = false;
}

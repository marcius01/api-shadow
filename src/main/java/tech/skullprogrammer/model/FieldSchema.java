package tech.skullprogrammer.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class FieldSchema {
    private String name;
    private String type;
    private String format;
    private boolean required;
    @Builder.Default
    private List<FakerSuggestion> suggestions = new ArrayList<>();
}

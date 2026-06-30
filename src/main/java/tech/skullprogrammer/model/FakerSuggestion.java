package tech.skullprogrammer.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FakerSuggestion {
    private String expression;
    private String label;
    private float score;
    private boolean autoSuggested;
}

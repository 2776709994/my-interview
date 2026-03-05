package com.edu.muc.app.modules.interview.dto;

import lombok.Data;
import java.util.List;

@Data
public class SkillDTO {
    private String id;
    private String name;
    private String description;
    private List<CategoryDTO> categories;
    private boolean isPreset;
    private String sourceJd;
    private String persona;
    private DisplayDTO display;

    @Data
    public static class CategoryDTO {
        private String key;
        private String label;
        private String priority; // CORE, NORMAL, ALWAYS_ONE
        private String ref;
        private boolean shared;
    }

    @Data
    public static class DisplayDTO {
        private String icon;
        private String gradient;
        private String iconBg;
        private String iconColor;
    }
}

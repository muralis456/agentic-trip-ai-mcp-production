package com.example.travel.dto;

import com.example.travel.model.PlanQualityScore;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class PlanValidationView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private PlanQualityScore quality;
    private List<String> errors = new ArrayList<>();
    private List<String> semanticNotes = new ArrayList<>();
    private List<ReviewItem> reviewItems = new ArrayList<>();

    public PlanQualityScore getQuality() {
        return quality;
    }

    public void setQuality(PlanQualityScore quality) {
        this.quality = quality;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors == null ? new ArrayList<>() : errors;
    }

    public List<String> getSemanticNotes() {
        return semanticNotes;
    }

    public void setSemanticNotes(List<String> semanticNotes) {
        this.semanticNotes = semanticNotes == null ? new ArrayList<>() : semanticNotes;
    }

    public List<ReviewItem> getReviewItems() {
        return reviewItems;
    }

    public void setReviewItems(List<ReviewItem> reviewItems) {
        this.reviewItems = reviewItems == null ? new ArrayList<>() : reviewItems;
    }

    public static class ReviewItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String level = "ok";
        private String text = "";

        public ReviewItem() {
        }

        public ReviewItem(String level, String text) {
            this.level = level == null ? "ok" : level;
            this.text = text == null ? "" : text;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}

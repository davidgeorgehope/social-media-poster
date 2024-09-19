package org.davidgeorgehope.socialmediaposter.model;

public class SocialMediaPost {
    private String content;
    private boolean postToTwitter;
    private boolean postToLinkedIn;

    // Getters and setters
    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isPostToTwitter() {
        return postToTwitter;
    }

    public void setPostToTwitter(boolean postToTwitter) {
        this.postToTwitter = postToTwitter;
    }

    public boolean isPostToLinkedIn() {
        return postToLinkedIn;
    }

    public void setPostToLinkedIn(boolean postToLinkedIn) {
        this.postToLinkedIn = postToLinkedIn;
    }
}
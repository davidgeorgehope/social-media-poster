package org.davidgeorgehope.socialmediaposter.service;

public interface AICompletionService {
    String generateCompletion(String systemPrompt, String userPrompt);
}
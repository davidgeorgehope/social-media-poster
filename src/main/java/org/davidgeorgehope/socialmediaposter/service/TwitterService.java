package org.davidgeorgehope.socialmediaposter.service;

import org.springframework.stereotype.Service;

@Service
public class TwitterService {

    public void postToTwitter(String content) {
        // TODO: Implement Twitter API integration
        System.out.println("Posting to Twitter: " + content);
    }
}
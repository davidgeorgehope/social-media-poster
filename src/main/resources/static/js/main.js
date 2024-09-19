document.getElementById('postForm').addEventListener('submit', function(e) {
    e.preventDefault();

    const postContent = document.getElementById('postContent').value;
    const postToTwitter = document.getElementById('postToTwitter').checked;
    const postToLinkedIn = document.getElementById('postToLinkedIn').checked;

    const postData = {
        content: postContent,
        postToTwitter: postToTwitter,
        postToLinkedIn: postToLinkedIn
    };

    fetch('/post', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(postData),
    })
    .then(response => response.text())
    .then(data => {
        alert(data);
        document.getElementById('postForm').reset();
    })
    .catch((error) => {
        console.error('Error:', error);
        alert('An error occurred while submitting the post.');
    });
});
document.addEventListener('DOMContentLoaded', () => {
    const chatForm = document.getElementById('chat-form');
    const messageInput = document.getElementById('message-input');
    const chatScreen = document.getElementById('chat-screen');
    const loadingOverlay = document.getElementById('loading-overlay');

    // Search Elements
    const searchBtn = document.getElementById('search-btn');
    const searchBar = document.getElementById('search-bar');
    const searchInput = document.getElementById('search-input');
    const searchNext = document.getElementById('search-next');
    const searchPrev = document.getElementById('search-prev');
    const searchClose = document.getElementById('search-close');
    const searchCount = document.getElementById('search-count');

    let searchMatches = [];
    let currentMatchIndex = -1;
    let lastMessageDate = null;

    // Initialize Marked options
    marked.setOptions({
        breaks: true, // Enable GFM line breaks
        gfm: true
    });

    // --- Search Functionality ---

    function toggleSearchBar() {
        const isHidden = searchBar.classList.contains('hidden');
        if (isHidden) {
            searchBar.classList.remove('hidden');
            searchInput.focus();
        } else {
            closeSearch();
        }
    }

    function closeSearch() {
        searchBar.classList.add('hidden');
        searchInput.value = '';
        clearHighlights();
    }

    function clearHighlights() {
        const highlights = document.querySelectorAll('.highlight');
        highlights.forEach(span => {
            const parent = span.parentNode;
            parent.replaceChild(document.createTextNode(span.textContent), span);
            parent.normalize(); // Merge adjacent text nodes
        });
        searchMatches = [];
        currentMatchIndex = -1;
        updateSearchCount();
    }

    function performSearch() {
        clearHighlights();
        const query = searchInput.value.trim();
        if (!query) return;

        const bubbles = document.querySelectorAll('.message-bubble');
        const regex = new RegExp(`(${query})`, 'gi');

        bubbles.forEach(bubble => {
            if (bubble.textContent.match(regex)) {
                // Careful replacement to preserve text nodes structure if possible, 
                // but simple innerHTML replacement is easier for simple text bubbles.
                // Assuming bubbles contain mostly text.
                const originalText = bubble.textContent;
                const newHtml = originalText.replace(regex, '<span class="highlight">$1</span>');
                bubble.innerHTML = newHtml;
            }
        });

        // Re-query for the created highlight spans
        searchMatches = document.querySelectorAll('.highlight');
        if (searchMatches.length > 0) {
            currentMatchIndex = searchMatches.length - 1; // Start at the latest message (bottom)
            focusMatch(currentMatchIndex);
        }
        updateSearchCount();
    }

    function focusMatch(index) {
        if (searchMatches.length === 0) return;

        // Remove active class from all
        searchMatches.forEach(match => match.classList.remove('active'));

        // Add active class to current
        const current = searchMatches[index];
        current.classList.add('active');
        
        // Scroll into view
        current.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    function nextMatch() {
        if (searchMatches.length === 0) return;
        currentMatchIndex++;
        if (currentMatchIndex >= searchMatches.length) {
            currentMatchIndex = 0;
        }
        focusMatch(currentMatchIndex);
        updateSearchCount();
    }

    function prevMatch() {
        if (searchMatches.length === 0) return;
        currentMatchIndex--;
        if (currentMatchIndex < 0) {
            currentMatchIndex = searchMatches.length - 1;
        }
        focusMatch(currentMatchIndex);
        updateSearchCount();
    }

    function updateSearchCount() {
        if (searchMatches.length === 0) {
            searchCount.textContent = '0/0';
        } else {
            searchCount.textContent = `${currentMatchIndex + 1}/${searchMatches.length}`;
        }
    }

    // Event Listeners for Search
    searchBtn.addEventListener('click', toggleSearchBar);
    searchClose.addEventListener('click', closeSearch);
    
    searchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            performSearch();
        }
    });

    searchNext.addEventListener('click', nextMatch);
    searchPrev.addEventListener('click', prevMatch);


    // --- Existing Chat Functionality ---

    function getCurrentTime() {
        const now = new Date();
        let hours = now.getHours();
        let minutes = now.getMinutes();
        const ampm = hours >= 12 ? '오후' : '오전';
        hours = hours % 12;
        hours = hours ? hours : 12; 
        minutes = minutes < 10 ? '0'+minutes : minutes;
        return `${ampm} ${hours}:${minutes}`;
    }

    function getFormattedDate(date) {
        const options = { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' };
        return date.toLocaleDateString('ko-KR', options);
    }

    function scrollToBottom() {
        chatScreen.scrollTop = chatScreen.scrollHeight;
    }

    function addMessage(text, type) {
        const now = new Date();
        const dateString = getFormattedDate(now);

        // Date Divider Logic
        if (lastMessageDate !== dateString) {
            const dateDiv = document.createElement('div');
            dateDiv.classList.add('chat-date');
            dateDiv.textContent = dateString;
            chatScreen.appendChild(dateDiv);
            lastMessageDate = dateString;
        }

        const row = document.createElement('div');
        row.classList.add('message-row', type);

        const time = getCurrentTime();

        if (type === 'sent') {
            // User message: Plain text
            row.innerHTML = `
                <div class="message-info">
                    <div class="message-bubble"></div>
                    <span class="message-time">${time}</span>
                </div>
            `;
            row.querySelector('.message-bubble').textContent = text;
        } else {
            // Bot message: Markdown rendered
            const renderedHtml = marked.parse(text);
            row.innerHTML = `
                <img src="/images/superdaddy_profile.svg" alt="SuperDaddy">
                <div class="message-content">
                    <span class="message-author">Super Daddy</span>
                    <div class="message-info">
                        <div class="message-bubble">${renderedHtml}</div>
                        <span class="message-time">${time}</span>
                    </div>
                </div>
            `;
        }
        
        chatScreen.appendChild(row);
        scrollToBottom();
    }

    chatForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const message = messageInput.value.trim();
        if (message.length === 0) return;

        // 1. Add User Message
        addMessage(message, 'sent');
        messageInput.value = '';

        // Show Loading
        loadingOverlay.classList.remove('hidden');

        try {
            const response = await fetch('/api/chat', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ message: message })
            });

            if (response.ok) {
                const data = await response.json();
                addMessage(data.response, 'received');
            } else {
                addMessage("오류가 발생했습니다. 다시 시도해주세요.", 'received');
            }
        } catch (error) {
            console.error('Error:', error);
            addMessage("서버 연결에 실패했습니다.", 'received');
        } finally {
            // Hide Loading
            loadingOverlay.classList.add('hidden');
        }
    });

    // Initial Welcome Message
    addMessage("안녕하세요! 초보 아빠를 위한 슈퍼 대디입니다. 무엇이든 물어보세요!", 'received');
});
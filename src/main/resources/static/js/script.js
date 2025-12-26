document.addEventListener('DOMContentLoaded', () => {
    const chatForm = document.getElementById('chat-form');
    const messageInput = document.getElementById('message-input');
    const chatScreen = document.getElementById('chat-screen');

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

    function scrollToBottom() {
        chatScreen.scrollTop = chatScreen.scrollHeight;
    }

    function addMessage(text, type) {
        const row = document.createElement('div');
        row.classList.add('message-row', type);

        const time = getCurrentTime();

        if (type === 'sent') {
            row.innerHTML = `
                <div class="message-info">
                    <div class="message-bubble">${text}</div>
                    <span class="message-time">${time}</span>
                </div>
            `;
        } else {
            row.innerHTML = `
                <img src="https://via.placeholder.com/50" alt="SuperDaddy">
                <div class="message-content">
                    <span class="message-author">Super Daddy</span>
                    <div class="message-info">
                        <div class="message-bubble">${text}</div>
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

        // 2. Show Loading (Optional, simpler to just wait for now or add a temporary bubble)
        // const loadingId = 'loading-' + Date.now();
        // addMessage('...', 'received'); 

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
        }
    });
});

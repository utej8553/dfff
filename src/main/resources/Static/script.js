/**
 * script.js
 * Handles WebSocket connection and manages the three areas: Code, Build Log, and Program Output/Input.
 */

// --- DOM ELEMENTS ---
const connectBtn = document.getElementById('connectBtn');
const runBtn = document.getElementById('runBtn');
const stopBtn = document.getElementById('stopBtn');
const codeArea = document.getElementById('codeArea');
const outputBox = document.getElementById('outputBox'); // Build Log
const programOutput = document.getElementById('programOutput'); // Program Output
const terminalInput = document.getElementById('terminalInput'); // Input Box
const sessionStatus = document.getElementById('sessionStatus');
const backendUrl = document.getElementById('backendUrl').textContent;

// --- STATE VARIABLES ---
let ws = null;
let isProcessRunning = false;
let isConnected = false;

// --- UTILITIES ---
function updateButtons(connect, run, stop) {
    connectBtn.disabled = !connect;
    runBtn.disabled = !run || !isConnected;
    stopBtn.disabled = !stop;
    terminalInput.disabled = !stop; // Input is enabled only when a process is running
}

function updateStatus(status, className, text) {
    sessionStatus.textContent = text;
    sessionStatus.className = `status-msg ${className}`;
}

function appendProgramOutput(data) {
    // Basic ANSI color support for streamed output
    const ansiRegex = /[\u001b\u009b][[()#;?]*(?:[0-9]{1,4}(?:;[0-9]{0,4})*)?[0-9A-ORZcf-nqr-uy=><]/g;
    let html = data.replace(ansiRegex, (match) => {
        // Simple placeholder for ANSI sequences (full support requires a library)
        if (match.includes('31')) return '<span style="color:var(--err)">'; // Red
        if (match.includes('32')) return '<span style="color:var(--ok)">';  // Green
        if (match.includes('0m')) return '</span>'; // Reset
        return '';
    });

    programOutput.innerHTML += html;
    programOutput.scrollTop = programOutput.scrollHeight;
}

// --- WEBSOCKET CONNECTION AND HANDLERS ---
function connectSession() {
    if (ws) ws.close();

    updateStatus('status-warn', 'Connecting...', 'Connecting...');
    outputBox.textContent = 'Attempting connection to backend...';
    programOutput.textContent = 'Attempting connection...';

    try {
        ws = new WebSocket(backendUrl);
    } catch (e) {
        outputBox.textContent += `\nERROR: Invalid WebSocket URL: ${e.message}`;
        return;
    }

    ws.onopen = () => {
        isConnected = true;
        updateStatus('status-ok', 'Connected (Idle)', 'Connected (Idle)');
        outputBox.textContent = 'WebSocket connection established. Ready to compile and run code.';
        programOutput.textContent = 'Session ready. Click RUN to execute code.';
        updateButtons(true, true, false);
    };

    ws.onmessage = (event) => {
        const message = event.data;
        const colonIndex = message.indexOf(':');

        if (colonIndex === -1) return;

        const type = message.substring(0, colonIndex);
        const content = message.substring(colonIndex + 1);

        switch (type) {
            case 'BUILD_LOG':
                outputBox.textContent += content + '\n';
                break;
            case 'OUTPUT':
                // Real-time output stream from the running program
                appendProgramOutput(content);
                break;
            case 'END':
                // Process finished or was killed
                isProcessRunning = false;
                updateStatus('status-ok', 'Connected (Idle)', 'Connected (Idle)');
                updateButtons(true, true, false);
                break;
            case 'ERROR':
                outputBox.textContent += `\n[ERROR] ${content}`;
                updateStatus('status-err', 'Error', 'Error');
                isProcessRunning = false;
                updateButtons(true, true, false);
                break;
        }
        outputBox.scrollTop = outputBox.scrollHeight;
    };

    ws.onclose = () => {
        isConnected = false;
        isProcessRunning = false;
        updateStatus('status-err', 'Disconnected', 'Disconnected');
        outputBox.textContent += '\n\nWebSocket connection closed.';
        programOutput.textContent += '\n[Session Closed]';
        updateButtons(true, false, false);
    };

    ws.onerror = (error) => {
        console.error('WebSocket Error:', error);
        updateStatus('status-err', 'Error', 'Error');
    };
}


// --- EVENT HANDLERS ---
connectBtn.onclick = connectSession;

runBtn.onclick = () => {
    if (ws && ws.readyState === WebSocket.OPEN && !isProcessRunning) {
        outputBox.textContent = '';
        programOutput.textContent = '';

        const code = codeArea.value;
        ws.send(`RUN:${code}`);

        isProcessRunning = true;
        updateStatus('status-warn', 'Running...', 'Running...');
        updateButtons(false, false, true);
    }
};

stopBtn.onclick = () => {
    if (ws && ws.readyState === WebSocket.OPEN && isProcessRunning) {
        // Send a kill command (we'll rely on the backend detecting the session close/force-kill)
        ws.close();
        updateStatus('status-err', 'Stopping...', 'Stopping...');
        updateButtons(false, false, false);
        // Reconnect immediately
        setTimeout(connectSession, 500);
    }
};

terminalInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        e.preventDefault();
        if (ws && ws.readyState === WebSocket.OPEN && isProcessRunning) {
            const input = terminalInput.value;
            ws.send("INPUT:" + input);
            appendProgramOutput(`\n[Input Sent: ${input}]\n`); // Show user what they sent
            terminalInput.value = ''; // Clear the input field
        } else {
            outputBox.textContent += '\n[System] Cannot send input. No active program running.';
        }
    }
});

// --- INITIALIZATION ---
window.onload = () => {
    // Note: The main application (OnlineCompilerApplication.java) must be running first.
    updateButtons(true, false, false);
};
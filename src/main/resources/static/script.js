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
// Removed: const backendUrl = document.getElementById('backendUrl').textContent;

// --- DYNAMIC URL FIX (CRITICAL) ---
// --- DYNAMIC URL FIX (CRITICAL) ---
// This calculates the host IP/DNS name from the browser's address bar.
const host = window.location.host.split(':')[0];
// const port = 8080; // <--- DELETE OR COMMENT OUT THIS LINE
const port = window.location.port ? `:${window.location.port}` : ''; // Use the existing port (80/443) or nothing
// Determine protocol (ws for http, wss for https)
const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';

// Construct the final, dynamic WebSocket URL
// DYNAMIC_BACKEND_URL will be ws://54.197.69.203/terminal
const DYNAMIC_BACKEND_URL = `${wsProtocol}//${host}${port}/terminal`; 
// ------------------------------------
// ------------------------------------

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
    const ansiRegex = /[\u001b\u009b][[()#;?]*(?:[0-9]{1,4}(?:;[0-9]{0,4})*)?[0-9A-ORZcf-nqr-uy=><]/g;
    let html = data.replace(ansiRegex, (match) => {
        if (match.includes('31')) return '<span style="color:var(--err)">';
        if (match.includes('32')) return '<span style="color:var(--ok)">';
        if (match.includes('0m')) return '</span>';
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
        // *** USE THE DYNAMICALLY CONSTRUCTED URL HERE ***
        ws = new WebSocket(DYNAMIC_BACKEND_URL);
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
                appendProgramOutput(content);
                break;
            case 'END':
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
        ws.close();
        updateStatus('status-err', 'Stopping...', 'Stopping...');
        updateButtons(false, false, false);
        setTimeout(connectSession, 500);
    }
};

terminalInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        e.preventDefault();
        if (ws && ws.readyState === WebSocket.OPEN && isProcessRunning) {
            const input = terminalInput.value;
            ws.send("INPUT:" + input);
            appendProgramOutput(`\n[Input Sent: ${input}]\n`);
            terminalInput.value = '';
        } else {
            outputBox.textContent += '\n[System] Cannot send input. No active program running.';
        }
    }
});

// --- INITIALIZATION ---
window.onload = () => {
    updateButtons(true, false, false);

};

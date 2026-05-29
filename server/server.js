const express = require('express');
const { WebSocketServer } = require('ws');
const http = require('http');
const fs = require('fs');
const path = require('path');

const LOG_FILE = path.join(__dirname, 'chats.jsonl');
const PORT = process.env.PORT || 3000;

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// In-memory map: player name -> WebSocket. Rebuilt naturally on connect/disconnect.
const connections = new Map();

app.use(express.static(path.join(__dirname, 'public')));

const JAR_DIR = path.join(__dirname, '..', 'build', 'libs');

app.get('/download', (_req, res) => {
    try {
        const jar = fs.readdirSync(JAR_DIR)
            .find(f => f.endsWith('.jar') && !f.endsWith('-sources.jar'));
        if (!jar) return res.status(404).send('JAR not built yet — run ./gradlew build first');
        res.download(path.join(JAR_DIR, jar), jar);
    } catch {
        res.status(404).send('Build directory not found');
    }
});

app.get('/chats', (_req, res) => {
    if (!fs.existsSync(LOG_FILE)) {
        return res.json([]);
    }
    try {
        const records = fs.readFileSync(LOG_FILE, 'utf8')
            .split('\n')
            .filter(l => l.trim())
            .map(l => JSON.parse(l));
        res.json(records);
    } catch {
        res.json([]);
    }
});

wss.on('connection', ws => {
    let name = null;

    ws.on('message', data => {
        let obj;
        try { obj = JSON.parse(data.toString()); } catch { return; }

        // hello — register before first message so the player is reachable immediately.
        if (obj.type === 'hello' && typeof obj.name === 'string') {
            name = obj.name;
            connections.set(name, ws);
            return;
        }

        if (typeof obj.from !== 'string' || typeof obj.to !== 'string' || typeof obj.msg !== 'string') return;

        // Anchor the from field to whatever name this socket registered.
        if (name) {
            obj.from = name;
        } else {
            name = obj.from;
            connections.set(name, ws);
        }

        const target = connections.get(obj.to);
        if (target && target.readyState === 1 /* OPEN */) {
            const record = { from: obj.from, to: obj.to, msg: obj.msg, ts: Date.now() };
            target.send(JSON.stringify(record));
            ws.send(JSON.stringify({ type: 'sent', to: obj.to, msg: obj.msg }));
            fs.appendFileSync(LOG_FILE, JSON.stringify(record) + '\n');
        } else {
            // Recipient not on relay — tell the sender to fall back to vanilla.
            ws.send(JSON.stringify({ type: 'fallback', to: obj.to, msg: obj.msg }));
        }
    });

    ws.on('close', () => {
        if (name) connections.delete(name);
    });
});

server.listen(PORT, () => {
    console.log(`clih-chat relay listening on port ${PORT}`);
});

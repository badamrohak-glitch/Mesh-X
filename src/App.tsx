import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity, ArrowDownToLine, ArrowUpFromLine, BatteryMedium, Bell, Bluetooth, Check,
  ChevronRight, CircleHelp, Download, FileText, Gauge, Globe2, Headphones, Image,
  Info, Laptop, Link2, Map, Menu, MessageCircle, MoreHorizontal, Network as NetworkIcon,
  Paperclip, Pause, Plus, Radio, RefreshCw, Router, Search, Send, Settings as SettingsIcon,
  Shield, Smartphone, Sparkles, Upload, UserRound, Users, Wifi, X, Zap
} from 'lucide-react';
import {
  BackgroundStatusData,
  connectPeer,
  DeviceIdentityData,
  disconnectPeer,
  DiscoveryCapabilitiesData,
  getBackgroundStatus,
  getDeviceIdentity,
  getDiscoveryCapabilities,
  getLocalStorageStats,
  getMessages,
  getNearbyDevices,
  isAndroidWebViewAvailable,
  LocalStorageStatsData,
  NearbyDevice,
  onBridgeEvent,
  requestBluetoothPermissions,
  sendMessage,
  startBackgroundService,
  startDiscovery,
  stopBackgroundService,
  stopDiscovery,
} from './bridge/nativeBridge';

type LayoutId = 'messenger' | 'map' | 'utility' | 'workspace' | 'timeline' | 'sheets';
type Page = 'home' | 'chats' | 'network' | 'transfers' | 'nearby' | 'gateway' | 'settings' | 'debug';

const layouts: { id: LayoutId; name: string; kicker: string; description: string; tone: string }[] = [
  { id: 'messenger', name: 'Messenger-First', kicker: '01 / communication', description: 'A familiar chat-led home for everyday offline conversations.', tone: 'violet' },
  { id: 'map', name: 'Network Map', kicker: '02 / topology', description: 'Make the mesh itself the primary surface and visual story.', tone: 'cyan' },
  { id: 'utility', name: 'Native Utility', kicker: '03 / practical', description: 'A fast, friendly Android-style command center.', tone: 'green' },
  { id: 'workspace', name: 'Split-Pane Workspace', kicker: '04 / power users', description: 'A persistent communication workspace for larger screens.', tone: 'blue' },
  { id: 'timeline', name: 'Activity Timeline', kicker: '05 / observability', description: 'Understand everything happening across the network, in order.', tone: 'amber' },
  { id: 'sheets', name: 'Modular Bottom-Sheet', kicker: '06 / mobile-native', description: 'Keep the network overview visible while tools slide into view.', tone: 'pink' },
];

const nav: { id: Page; label: string; icon: React.ReactNode }[] = [
  { id: 'home', label: 'Home', icon: <Gauge size={17} /> },
  { id: 'chats', label: 'Chats', icon: <MessageCircle size={17} /> },
  { id: 'network', label: 'Network', icon: <NetworkIcon size={17} /> },
  { id: 'transfers', label: 'Transfers', icon: <ArrowUpFromLine size={17} /> },
  { id: 'nearby', label: 'Nearby Devices', icon: <Radio size={17} /> },
  { id: 'gateway', label: 'Gateway', icon: <Globe2 size={17} /> },
  { id: 'settings', label: 'Settings', icon: <SettingsIcon size={17} /> },
  { id: 'debug', label: 'Debug', icon: <Activity size={17} /> },
];

export interface ChatContact {
  id: string;
  name: string;
  initials: string;
  color: string;
  preview: string;
  time: string;
  unread: number;
  online: boolean;
  peerDeviceId: string;
  isMeshBroadcast?: boolean;
}

export interface ChatMessage {
  id: string;
  sender: 'me' | 'them';
  senderName?: string;
  text?: string;
  file?: {
    name: string;
    size: string;
    dataUrl?: string;
  };
  time: string;
  status: 'sending' | 'sent' | 'delivered';
  hopCount?: number;
}

const initialChats: ChatContact[] = [
  { id: 'alex', name: 'Alex Morgan', initials: 'AM', color: 'lavender', preview: 'Can you see the route change?', time: '2m', unread: 2, online: true, peerDeviceId: 'device_alex_8f21' },
  { id: 'field_team', name: 'Field Team (Mesh Broadcast)', initials: 'FT', color: 'mint', preview: 'Mesh broadcast channel', time: '18m', unread: 0, online: true, peerDeviceId: '*', isMeshBroadcast: true },
  { id: 'noah', name: 'Noah Chen', initials: 'NC', color: 'peach', preview: 'Delivered · 1.4 MB', time: '1h', unread: 0, online: false, peerDeviceId: 'device_noah_3e14' },
];

const devices = [
  { name: 'Alex’s Pixel 8', kind: 'This device', icon: <Smartphone size={18} />, quality: 'Excellent', hop: '0 hops', battery: '86%' },
  { name: 'Maya’s Laptop', kind: 'Direct peer', icon: <Laptop size={18} />, quality: 'Good', hop: '1 hop', battery: '—' },
  { name: 'Noah’s Tablet', kind: 'Relay device', icon: <TabletIcon />, quality: 'Fair', hop: '2 hops', battery: '62%' },
];

const events = [
  { time: '10:42:18', type: 'Device connected', detail: 'Maya’s Laptop joined through direct link', color: 'green', targetPage: 'nearby' as Page },
  { time: '10:39:04', type: 'File received', detail: 'project-notes.pdf · 1.4 MB', color: 'blue', targetPage: 'transfers' as Page },
  { time: '10:31:52', type: 'Route updated', detail: 'Preferred path changed to 2 hops', color: 'violet', targetPage: 'network' as Page },
  { time: '09:58:20', type: 'Message delivered', detail: 'Field Team · 3 recipients', color: 'amber', targetPage: 'chats' as Page },
];

function TabletIcon() { return <span className="tablet-icon" />; }

function pageFromPath(pathname: string): Page {
  const value = pathname.replace(/^\//, '') as Page;
  return nav.some(item => item.id === value) ? value : 'home';
}

function triggerDownload(fileName: string, contentOrDataUrl: string) {
  const link = document.createElement('a');
  if (contentOrDataUrl.startsWith('data:')) {
    link.href = contentOrDataUrl;
  } else {
    const blob = new Blob([contentOrDataUrl], { type: 'text/plain;charset=utf-8' });
    link.href = URL.createObjectURL(blob);
  }
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

type IdentityStatus = 'loading' | 'ready' | 'unavailable' | 'error';

export default function App() {
  const [layout, setLayout] = useState<LayoutId>('messenger');
  const [page, setPage] = useState<Page>(() => pageFromPath(window.location.pathname));
  const [query, setQuery] = useState('');
  const [toast, setToast] = useState('');
  const [selectedDevice, setSelectedDevice] = useState(0);
  const [sheet, setSheet] = useState<string | null>(null);
  const [identity, setIdentity] = useState<DeviceIdentityData | null>(null);
  const [identityStatus, setIdentityStatus] = useState<IdentityStatus>(() => isAndroidWebViewAvailable() ? 'loading' : 'unavailable');
  const [identityError, setIdentityError] = useState<string | null>(null);

  // Mesh Peer & Conversation state
  const [contacts, setContacts] = useState<ChatContact[]>(initialChats);
  const [selectedChatId, setSelectedChatId] = useState<string>('alex');
  const [messagesByChat, setMessagesByChat] = useState<Record<string, ChatMessage[]>>({
    alex: [
      { id: 'm1', sender: 'them', text: 'Hey, are you seeing the route change?', time: '10:39', status: 'delivered' },
      { id: 'm2', sender: 'me', text: 'Yep — Maya’s laptop is relaying it now.', time: '10:40', status: 'sent', hopCount: 1 },
      { id: 'm3', sender: 'them', file: { name: 'project-notes.pdf', size: '1.4 MB' }, time: '10:41', status: 'delivered' },
      { id: 'm4', sender: 'them', text: 'Perfect. Sending the latest notes now.', time: '10:42', status: 'delivered' },
    ],
    field_team: [
      { id: 'm5', sender: 'them', senderName: 'Maya', text: 'Field Team broadcast online over BLE mesh.', time: '10:30', status: 'delivered' },
      { id: 'm6', sender: 'me', text: 'Ready for multi-peer packet relay.', time: '10:32', status: 'sent', hopCount: 0 },
    ],
    noah: [
      { id: 'm7', sender: 'them', text: 'Noah’s tablet connected via 2 hops.', time: '09:40', status: 'delivered' },
    ],
  });

  const active = layouts.find(x => x.id === layout)!;

  const notify = useCallback((message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(''), 2400);
  }, []);

  const go = useCallback((next: Page) => {
    setPage(next);
    setSheet(null);
    if (window.location.pathname !== `/${next}`) {
      window.history.pushState({}, '', next === 'home' ? '/' : `/${next}`);
    }
  }, []);

  const loadIdentity = useCallback(async (showFeedback = false) => {
    if (isAndroidWebViewAvailable()) { setIdentityStatus('loading'); }
    const res = await getDeviceIdentity();
    if (res.ok && res.data && res.data.deviceId && res.data.publicKey) {
      setIdentity(res.data);
      setIdentityStatus('ready');
      setIdentityError(null);
      if (showFeedback) { notify('Device identity verified'); }
      return;
    }
    setIdentity(null);
    if (res.error?.code === 'BRIDGE_UNAVAILABLE') {
      setIdentityStatus('unavailable');
      setIdentityError(res.error.message);
      if (showFeedback) { notify('Android native bridge unavailable in browser preview'); }
      return;
    }
    const msg = res.error ? `${res.error.code}: ${res.error.message}` : 'Unable to retrieve device identity.';
    setIdentityStatus('error');
    setIdentityError(msg);
    if (showFeedback) { notify('Unable to load device identity'); }
  }, [notify]);

  // Sync peers into chat contacts
  const syncNearbyContacts = useCallback(async () => {
    if (!isAndroidWebViewAvailable()) return;
    const res = await getNearbyDevices();
    if (res.ok && res.data?.devices) {
      setContacts(prev => {
        const next = [...prev];
        for (const peer of res.data.devices) {
          const peerId = peer.deviceId || peer.address;
          const existing = next.find(c => c.peerDeviceId === peerId);
          if (!existing) {
            const name = peer.name || `MESH-X Peer (${peer.fingerprint || peer.address.slice(-4)})`;
            next.push({
              id: `peer_${peerId}`,
              name,
              initials: name.split(' ').map(s => s[0]).join('').slice(0, 2).toUpperCase() || 'MX',
              color: 'cyan',
              preview: peer.connectionState === 'connected' ? 'Connected directly via BLE' : 'Discovered nearby',
              time: 'now',
              unread: 0,
              online: peer.connectionState === 'connected',
              peerDeviceId: peerId,
            });
          }
        }
        return next;
      });
    }
  }, []);

  useEffect(() => {
    void loadIdentity(false);
    void syncNearbyContacts();

    return onBridgeEvent(event => {
      if (event.type === 'BRIDGE_READY') {
        void loadIdentity(false);
        void syncNearbyContacts();
      } else if (event.type === 'DEVICE_FOUND' || event.type === 'PEER_CONNECTION_CHANGED') {
        void syncNearbyContacts();
      } else if (event.type === 'MESSAGE_RECEIVED') {
        const payload = event.payload as { messageId?: string; peerDeviceId?: string; body?: string; timestamp?: number; hopCount?: number };
        const peerId = payload.peerDeviceId || '*';
        const body = payload.body || '';
        const timeStr = new Date(payload.timestamp || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

        // Parse potential file message
        let fileObj: { name: string; size: string; dataUrl?: string } | undefined;
        let textMsg = body;
        try {
          if (body.startsWith('{') && body.includes('"type":"file"')) {
            const parsed = JSON.parse(body);
            fileObj = { name: parsed.name, size: parsed.size, dataUrl: parsed.dataUrl };
            textMsg = '';
          }
        } catch (_) {}

        const newMsg: ChatMessage = {
          id: payload.messageId || `msg_${Date.now()}`,
          sender: 'them',
          text: textMsg || undefined,
          file: fileObj,
          time: timeStr,
          status: 'delivered',
          hopCount: payload.hopCount,
        };

        setMessagesByChat(prev => {
          const chatKey = prev[peerId] ? peerId : (prev['field_team'] ? 'field_team' : 'alex');
          return {
            ...prev,
            [chatKey]: [...(prev[chatKey] || []), newMsg],
          };
        });

        notify(`New message received via mesh route (${payload.hopCount ?? 0} hops)`);
      } else if (event.type === 'MESSAGE_SENT') {
        // Message acknowledged by native bridge
      }
    });
  }, [loadIdentity, syncNearbyContacts, notify]);

  useEffect(() => {
    const onPop = () => setPage(pageFromPath(window.location.pathname));
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  useEffect(() => {
    const selector = '.content .section,.content .command-hero,.content .command-radar,.content .command-stats,.content .command-panel,.content .command-actions,.content .timeline-event,.content .transfer-row,.content .nearby-row,.content .gateway-card,.content .settings-section,.content .network-stat';
    const elements = Array.from(document.querySelectorAll<HTMLElement>(selector));
    elements.forEach((element, index) => {
      element.classList.add('reveal-on-scroll');
      element.style.setProperty('--reveal-delay', `${Math.min(index % 5, 4) * 70}ms`);
    });
    const observer = new IntersectionObserver(entries => entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      }
    }), { threshold: .12 });
    elements.forEach(element => observer.observe(element));
    return () => observer.disconnect();
  }, [page, layout]);

  const handleSendMessage = useCallback(async (text: string, fileAttachment?: { name: string; size: string; dataUrl?: string }) => {
    if (!text.trim() && !fileAttachment) return;
    const activeContact = contacts.find(c => c.id === selectedChatId) || contacts[0];
    const peerDeviceId = activeContact.peerDeviceId || '*';
    const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const msgId = `m_${Date.now()}`;

    const newMsg: ChatMessage = {
      id: msgId,
      sender: 'me',
      text: text.trim() || undefined,
      file: fileAttachment,
      time: timeStr,
      status: 'sending',
      hopCount: 0,
    };

    setMessagesByChat(prev => ({
      ...prev,
      [selectedChatId]: [...(prev[selectedChatId] || []), newMsg],
    }));

    // Update preview
    setContacts(prev => prev.map(c => c.id === selectedChatId ? {
      ...c,
      preview: fileAttachment ? `Sent file · ${fileAttachment.name}` : text.trim(),
      time: 'Just now',
    } : c));

    // Send across native Android bridge if available
    if (isAndroidWebViewAvailable()) {
      const payloadBody = fileAttachment
        ? JSON.stringify({ type: 'file', name: fileAttachment.name, size: fileAttachment.size, dataUrl: fileAttachment.dataUrl })
        : text.trim();
      const res = await sendMessage(peerDeviceId, payloadBody);
      if (res.ok) {
        setMessagesByChat(prev => ({
          ...prev,
          [selectedChatId]: (prev[selectedChatId] || []).map(m => m.id === msgId ? { ...m, status: 'sent' } : m),
        }));
        notify('Message transmitted over Bluetooth mesh');
      } else {
        notify(res.error?.message ?? 'Failed to send over bridge');
      }
    } else {
      setMessagesByChat(prev => ({
        ...prev,
        [selectedChatId]: (prev[selectedChatId] || []).map(m => m.id === msgId ? { ...m, status: 'sent' } : m),
      }));
      notify('Message sent (local preview mode)');
    }
  }, [contacts, selectedChatId, notify]);

  const filteredChats = useMemo(
    () => contacts.filter(c => (c.name + c.preview).toLowerCase().includes(query.toLowerCase())),
    [contacts, query]
  );

  return (
    <div className={`app theme-${active.tone} layout-${layout}`}>
      <div className="ambient-field" aria-hidden="true">
        <span className="ambient-symbol symbol-a">✧</span>
        <span className="ambient-symbol symbol-b">⌁</span>
        <span className="ambient-symbol symbol-c">◐</span>
        <span className="ambient-symbol symbol-d">⟡</span>
        <span className="ambient-symbol symbol-e">·</span>
        <span className="ambient-orbit orbit-one" />
        <span className="ambient-orbit orbit-two" />
      </div>

      <header className="topbar">
        <div className="brand" onClick={() => go('home')}>
          <div className="brand-mark"><span /><span /><span /></div>
          <div><strong>MESH<span>-X</span></strong><small>LAYOUT LAB</small></div>
        </div>
        <div className="top-center">
          <span className="eyebrow">OFFLINE-FIRST P2P MESH</span>
          <span className="live-pill"><i /><span className="live-wave"><b /><b /><b /><b /></span> Mesh active</span>
        </div>
        <div className="top-actions">
          <button className="icon-btn" title="View Debug & Logs" onClick={() => go('debug')}>
            <Bell size={17} /><b>2</b>
          </button>
          <button className="avatar" title="Device Settings" onClick={() => go('settings')}>
            {identityStatus === 'ready' ? 'MX' : 'JD'}
          </button>
        </div>
      </header>

      <div className="shell">
        <aside className="sidebar">
          <div className="layout-picker">
            <div className="eyebrow">Choose a direction</div>
            <div className="layout-list">
              {layouts.map(item => (
                <button
                  key={item.id}
                  className={layout === item.id ? 'selected' : ''}
                  onClick={() => { setLayout(item.id); notify(`${item.name} selected`); }}
                >
                  <span className={`picker-dot ${item.tone}`} />
                  <span><strong>{item.name}</strong><small>{item.kicker}</small></span>
                  {layout === item.id && <Check size={15} />}
                </button>
              ))}
            </div>
          </div>
          <div className="side-nav">
            <div className="eyebrow">Product surfaces</div>
            {nav.map(item => (
              <button
                key={item.id}
                className={page === item.id ? 'active' : ''}
                onClick={() => go(item.id)}
              >
                {item.icon}
                <span>{item.label}</span>
                {['transfers', 'nearby'].includes(item.id) && <em>•</em>}
              </button>
            ))}
          </div>
          <div className="sidebar-foot">
            <div className="sim-card" onClick={() => go('settings')} style={{ cursor: 'pointer' }}>
              <div><span className="status-dot" /> {isAndroidWebViewAvailable() ? 'NATIVE BLE ACTIVE' : 'PREVIEW MODE'}</div>
              <p>Direct peer-to-peer communication with Android Keystore security.</p>
            </div>
            <div className="foot-row" onClick={() => go('debug')} style={{ cursor: 'pointer' }}>
              <span>v1.0.0 (Phase 5)</span>
              <CircleHelp size={15} />
            </div>
          </div>
        </aside>

        <main className="content">
          <div className="page-head">
            <div>
              <span className="eyebrow">{active.kicker}</span>
              <h1>{pageTitle(page, active.name)}</h1>
              <p>{pageSubtitle(page, active.description)}</p>
            </div>
            <div className="head-actions">
              <button className="ghost-btn" onClick={() => {
                if (navigator.clipboard) {
                  navigator.clipboard.writeText(window.location.href);
                }
                notify('Page link copied');
              }}>
                <Link2 size={15} /> Share
              </button>
              <button className="primary-btn" onClick={() => setSheet('Quick Actions')}>
                <Plus size={16} /> New action
              </button>
            </div>
          </div>

          {page === 'home' && (
            <Home
              layout={layout}
              go={go}
              setSheet={setSheet}
              selectedDevice={selectedDevice}
              setSelectedDevice={setSelectedDevice}
              notify={notify}
            />
          )}

          {page === 'chats' && (
            <Chats
              query={query}
              setQuery={setQuery}
              contacts={filteredChats}
              selectedChatId={selectedChatId}
              onSelectChat={setSelectedChatId}
              messages={messagesByChat[selectedChatId] || []}
              onSendMessage={handleSendMessage}
              notify={notify}
              go={go}
            />
          )}

          {page === 'network' && (
            <Network
              selectedDevice={selectedDevice}
              setSelectedDevice={setSelectedDevice}
              notify={notify}
              go={go}
            />
          )}

          {page === 'transfers' && <Transfers notify={notify} go={go} />}

          {page === 'nearby' && <Nearby notify={notify} go={go} />}

          {page === 'gateway' && <Gateway notify={notify} go={go} />}

          {page === 'settings' && (
            <Settings
              notify={notify}
              identity={identity}
              identityStatus={identityStatus}
              identityError={identityError}
              onRefreshIdentity={() => void loadIdentity(true)}
              go={go}
            />
          )}

          {page === 'debug' && <Debug go={go} notify={notify} />}
        </main>
      </div>

      {sheet && (
        <BottomSheet
          title={sheet}
          close={() => setSheet(null)}
          go={go}
          notify={notify}
        />
      )}

      {toast && (
        <div className="toast">
          <Check size={15} />{toast}
        </div>
      )}
    </div>
  );
}

function pageTitle(page: Page, layout: string) {
  const names: { [key in Page]: string } = {
    home: layout,
    chats: 'Conversations & Mesh Chat',
    network: 'Network topology',
    transfers: 'Transfers & File Sharing',
    nearby: 'Nearby devices & BLE Peers',
    gateway: 'Gateway access',
    settings: 'Settings & Device Identity',
    debug: 'Debug console & Packet Stream',
  };
  return names[page];
}

function pageSubtitle(page: Page, description: string) {
  if (page === 'home') return description;
  const s: { [key in Page]: string } = {
    home: '',
    chats: 'Send encrypted messages and files directly over multi-hop Bluetooth mesh.',
    network: 'Select a device to inspect routes, hops, and link quality.',
    transfers: 'Upload and transfer files through the local mesh without central servers.',
    nearby: 'Discover and connect to nearby MESH-X phones via Bluetooth LE.',
    gateway: 'Optional internet uplink sharing — isolated from local communication.',
    settings: 'Persistent Keystore cryptographic identity and background service preferences.',
    debug: 'Live stream of packet routing, mesh relay events, and bridge activity.',
  };
  return s[page];
}

function Home({
  layout, go, setSheet, selectedDevice, setSelectedDevice, notify
}: {
  layout: LayoutId; go: (p: Page) => void; setSheet: (s: string) => void;
  selectedDevice: number; setSelectedDevice: (n: number) => void; notify: (s: string) => void;
}) {
  if (layout === 'messenger') return <MessengerHome go={go} notify={notify} />;
  if (layout === 'map') return <MapHome go={go} selectedDevice={selectedDevice} setSelectedDevice={setSelectedDevice} />;
  if (layout === 'utility') return <UtilityHome go={go} notify={notify} />;
  if (layout === 'workspace') return <WorkspaceHome go={go} notify={notify} />;
  if (layout === 'timeline') return <TimelineHome go={go} />;
  return <SheetHome go={go} setSheet={setSheet} />;
}

function Section({ title, action, children }: { title: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="section">
      <div className="section-head">
        <h2>{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function MessengerHome({ go, notify }: { go: (p: Page) => void; notify: (s: string) => void }) {
  return (
    <div className="command-home">
      <div className="command-hero">
        <div className="command-copy">
          <span className="eyebrow">MESH CONTROL / LIVE OVERVIEW</span>
          <h2>Your network is<br /><em>healthy.</em></h2>
          <p>Devices are connected locally. Messages and files move without internet gateways.</p>
          <div className="hero-actions">
            <button className="primary-btn" onClick={() => go('chats')}>
              <MessageCircle size={16} /> Open conversations
            </button>
            <button className="ghost-btn" onClick={() => go('network')}>
              <NetworkIcon size={15} /> Inspect routes
            </button>
          </div>
        </div>
        <div className="health-orb" onClick={() => go('network')} style={{ cursor: 'pointer' }}>
          <div className="orb-ring"><strong>98</strong><span>%</span></div>
          <small>mesh health</small>
          <i className="orb-spark spark-one" /><i className="orb-spark spark-two" />
        </div>
        <div className="command-hero-foot">
          <span><i className="status-dot" /> Local network online</span>
          <span onClick={() => go('debug')} style={{ cursor: 'pointer' }}>Last recalculated 11 sec ago</span>
        </div>
      </div>

      <div className="command-radar">
        <div className="radar-head">
          <div><span className="eyebrow">ROUTE RADAR</span><strong>See the mesh breathe.</strong></div>
          <button className="icon-btn" title="Refresh topology" onClick={() => notify('Topology refreshed')}>
            <RefreshCw size={16} />
          </button>
        </div>
        <div className="topology command-topology">
          <div className="route-line l1" />
          <div className="route-line l2" />
          <div className="route-line l3" />
          <div className="route-line l4" />
          <MapNode x="center" label="You" icon={<Smartphone />} active onClick={() => go('network')} />
          <MapNode x="nw" label="Maya" icon={<Laptop />} onClick={() => go('network')} />
          <MapNode x="ne" label="Noah" icon={<TabletIcon />} onClick={() => go('network')} />
          <MapNode x="sw" label="Relay" icon={<Router />} onClick={() => go('network')} />
          <MapNode x="se" label="Gateway" icon={<Globe2 />} muted onClick={() => go('gateway')} />
        </div>
        <div className="radar-foot">
          <span><i className="legend-green" /> Preferred route</span>
          <span><i className="legend-blue" /> Relay path</span>
          <button className="text-btn" onClick={() => go('network')}>
            Open full map <ChevronRight size={14} />
          </button>
        </div>
      </div>

      <div className="command-stats">
        <div onClick={() => go('network')} style={{ cursor: 'pointer' }}>
          <span className="stat-icon cyan"><Activity /></span>
          <strong>2</strong><small>active routes</small><b>+1 this hour</b>
        </div>
        <div onClick={() => go('debug')} style={{ cursor: 'pointer' }}>
          <span className="stat-icon violet"><Zap /></span>
          <strong>24<span>ms</span></strong><small>median latency</small><b>−8% vs yesterday</b>
        </div>
        <div onClick={() => go('settings')} style={{ cursor: 'pointer' }}>
          <span className="stat-icon green"><BatteryMedium /></span>
          <strong>86<span>%</span></strong><small>device battery</small><b>healthy reserve</b>
        </div>
        <div onClick={() => go('gateway')} style={{ cursor: 'pointer' }}>
          <span className="stat-icon amber"><Globe2 /></span>
          <strong>OFF</strong><small>internet gateway</small><b className="amber-text">local only</b>
        </div>
      </div>

      <div className="command-columns">
        <div className="command-panel">
          <div className="command-panel-head">
            <div><span className="eyebrow">CONNECTED DEVICES</span><strong>Who is on your mesh?</strong></div>
            <button className="text-btn" onClick={() => go('nearby')}>View all <ChevronRight size={14} /></button>
          </div>
          {devices.slice(0, 3).map((d, i) => (
            <button className="device-row" key={d.name} onClick={() => go('network')}>
              <span className="device-icon">{d.icon}</span>
              <span><strong>{d.name}</strong><small>{d.kind} · {d.hop}</small></span>
              <span className="device-quality"><i className={i === 2 ? 'fair' : ''} />{d.quality}</span>
              <ChevronRight size={15} />
            </button>
          ))}
        </div>

        <div className="command-panel activity-panel">
          <div className="command-panel-head">
            <div><span className="eyebrow">RECENT ACTIVITY</span><strong>Quiet, but alive.</strong></div>
            <button className="icon-btn" title="View Debug Logs" onClick={() => go('debug')}>
              <MoreHorizontal size={16} />
            </button>
          </div>
          {events.slice(0, 3).map(e => (
            <div className="activity-event" key={e.time} onClick={() => go(e.targetPage)} style={{ cursor: 'pointer' }}>
              <span className={`event-dot ${e.color}`} />
              <div><strong>{e.type}</strong><small>{e.detail}</small></div>
              <time>{e.time}</time>
            </div>
          ))}
        </div>
      </div>

      <div className="command-actions">
        <Quick icon={<Upload />} title="Send a file" onClick={() => go('transfers')} />
        <Quick icon={<Radio />} title="Find nearby" onClick={() => go('nearby')} />
        <Quick icon={<Globe2 />} title="Check gateway" onClick={() => go('gateway')} />
      </div>
    </div>
  );
}

function ChatRow({ chat, onClick }: { chat: ChatContact; onClick: () => void }) {
  return (
    <button className="chat-row" onClick={onClick}>
      <span className={`avatar avatar-${chat.color}`}>{chat.initials}</span>
      <span className="chat-info">
        <strong>{chat.name}{chat.online && <i className="online-dot" />}</strong>
        <small>{chat.preview}</small>
      </span>
      <span className="chat-meta">
        <small>{chat.time}</small>
        {chat.unread > 0 && <b>{chat.unread}</b>}
      </span>
    </button>
  );
}

function Quick({ icon, title, onClick }: { icon: React.ReactNode; title: string; onClick: () => void }) {
  return (
    <button className="quick-card" onClick={onClick}>
      {icon}
      <span>{title}</span>
      <ChevronRight size={15} />
    </button>
  );
}

function MapHome({
  go, selectedDevice, setSelectedDevice
}: {
  go: (p: Page) => void; selectedDevice: number; setSelectedDevice: (n: number) => void;
}) {
  return (
    <div className="map-layout">
      <div className="map-card">
        <div className="map-toolbar">
          <div><span className="eyebrow">LIVE TOPOLOGY</span><strong>Local mesh · 5 nodes</strong></div>
          <button className="icon-btn" onClick={() => go('network')}><MoreHorizontal size={17} /></button>
        </div>
        <div className="topology">
          <div className="route-line l1" /><div className="route-line l2" /><div className="route-line l3" /><div className="route-line l4" />
          <MapNode x="center" label="You" icon={<Smartphone />} active selected={selectedDevice === 0} onClick={() => setSelectedDevice(0)} />
          <MapNode x="nw" label="Maya" icon={<Laptop />} selected={selectedDevice === 1} onClick={() => setSelectedDevice(1)} />
          <MapNode x="ne" label="Noah" icon={<TabletIcon />} selected={selectedDevice === 2} onClick={() => setSelectedDevice(2)} />
          <MapNode x="sw" label="Relay" icon={<Router />} onClick={() => setSelectedDevice(3)} />
          <MapNode x="se" label="Gateway" icon={<Globe2 />} muted onClick={() => go('gateway')} />
        </div>
        <div className="map-legend">
          <span><i className="legend-green" /> Direct</span>
          <span><i className="legend-blue" /> 1 hop</span>
          <span><i className="legend-dashed" /> Alternative path</span>
        </div>
      </div>
      <div className="map-detail">
        <div className="detail-label">SELECTED DEVICE</div>
        <div className="device-title">
          <span className="device-icon"><Smartphone size={20} /></span>
          <div>
            <h3>{devices[selectedDevice % 3].name}</h3>
            <p>{devices[selectedDevice % 3].kind} · {devices[selectedDevice % 3].hop}</p>
          </div>
          <button className="icon-btn" onClick={() => go('nearby')}><MoreHorizontal size={16} /></button>
        </div>
        <div className="metric-row">
          <Metric label="Quality" value={devices[selectedDevice % 3].quality} />
          <Metric label="Latency" value="24 ms" />
          <Metric label="Battery" value={devices[selectedDevice % 3].battery} />
        </div>
        <button className="primary-btn full" onClick={() => go('chats')}>
          <MessageCircle size={16} /> Message device
        </button>
      </div>
    </div>
  );
}

function MapNode({
  x, label, icon, active, selected, muted, onClick
}: {
  x: string; label: string; icon: React.ReactNode; active?: boolean; selected?: boolean; muted?: boolean; onClick: () => void;
}) {
  return (
    <button className={`map-node ${x} ${active ? 'active' : ''} ${selected ? 'node-selected' : ''} ${muted ? 'muted' : ''}`} onClick={onClick}>
      <span className="node-ring">{icon}</span>
      <strong>{label}</strong>
      <small>{active ? 'Your device' : muted ? 'Internet' : 'Connected'}</small>
    </button>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return <div><small>{label}</small><strong>{value}</strong></div>;
}

function UtilityHome({ go, notify }: { go: (p: Page) => void; notify: (s: string) => void }) {
  return (
    <div className="utility-layout">
      <div className="utility-status" onClick={() => go('nearby')} style={{ cursor: 'pointer' }}>
        <div className="status-icon"><Wifi /></div>
        <div>
          <span className="eyebrow">NETWORK STATUS</span>
          <h2>Connected locally</h2>
          <p>No internet required · Devices nearby</p>
        </div>
        <span className="status-check"><Check size={15} /></span>
      </div>
      <div className="stat-grid">
        <div onClick={() => go('nearby')} style={{ cursor: 'pointer' }}><Stat value="5" label="Devices" icon={<Users />} /></div>
        <div onClick={() => go('network')} style={{ cursor: 'pointer' }}><Stat value="2" label="Routes" icon={<RouteIcon />} /></div>
        <div onClick={() => go('debug')} style={{ cursor: 'pointer' }}><Stat value="98%" label="Health" icon={<Activity />} /></div>
      </div>
      <Section title="Quick actions">
        <div className="action-list">
          <ActionRow icon={<MessageCircle />} title="Start a conversation" sub="Choose a peer or group" onClick={() => go('chats')} />
          <ActionRow icon={<Send />} title="Send a file" sub="Photos, videos or documents" onClick={() => go('transfers')} />
          <ActionRow icon={<Radio />} title="Find nearby devices" sub="Discover peers around you" onClick={() => go('nearby')} />
        </div>
      </Section>
      <Section title="Recent activity" action={<button className="text-btn" onClick={() => go('debug')}>See all <ChevronRight size={14} /></button>}>
        <div className="activity-mini">
          {events.slice(0, 3).map(e => (
            <div key={e.time} onClick={() => go(e.targetPage)} style={{ cursor: 'pointer' }}>
              <span className={`event-dot ${e.color}`} />
              <div><strong>{e.type}</strong><small>{e.detail}</small></div>
              <time>{e.time}</time>
            </div>
          ))}
        </div>
      </Section>
      <button className="soft-banner" onClick={() => go('gateway')}>
        <Globe2 />
        <span><strong>Internet gateway optional</strong><small>Click to inspect gateway configuration.</small></span>
        <ChevronRight />
      </button>
    </div>
  );
}

function RouteIcon() { return <span className="route-icon"><span /><span /><span /></span>; }

function Stat({ value, label, icon }: { value: string; label: string; icon: React.ReactNode }) {
  return <div className="stat"><span>{icon}</span><strong>{value}</strong><small>{label}</small></div>;
}

function ActionRow({ icon, title, sub, onClick }: { icon: React.ReactNode; title: string; sub: string; onClick: () => void }) {
  return (
    <button className="action-row" onClick={onClick}>
      <span>{icon}</span>
      <div><strong>{title}</strong><small>{sub}</small></div>
      <ChevronRight />
    </button>
  );
}

function WorkspaceHome({ go, notify }: { go: (p: Page) => void; notify: (s: string) => void }) {
  return (
    <div className="workspace-layout">
      <aside className="workspace-list">
        <div className="workspace-search"><Search size={15} /><input placeholder="Search" /></div>
        <div className="list-label" onClick={() => go('nearby')} style={{ cursor: 'pointer' }}>
          CONVERSATIONS <Plus size={14} />
        </div>
        {initialChats.map(c => <ChatRow key={c.name} chat={c} onClick={() => go('chats')} />)}
        <div className="list-label spaced">NETWORK</div>
        <button className="workspace-link" onClick={() => go('nearby')}><Radio size={16} /><span>Nearby devices</span><b>3</b></button>
        <button className="workspace-link" onClick={() => go('transfers')}><ArrowUpFromLine size={16} /><span>Transfers</span><b>2</b></button>
      </aside>
      <div className="workspace-main">
        <div className="workspace-hero">
          <div>
            <span className="eyebrow">WORKSPACE OVERVIEW</span>
            <h2>Everything connected.</h2>
            <p>Communicate, route and transfer in one calm surface.</p>
          </div>
          <div className="workspace-graph" onClick={() => go('network')} style={{ cursor: 'pointer' }}><span /><span /><span /><span /><span /></div>
        </div>
        <div className="workspace-columns">
          <div>
            <Section title="Recent activity" action={<button className="text-btn" onClick={() => go('debug')}>Open debug <ChevronRight size={14} /></button>}>
              <div className="activity-mini">
                {events.slice(0, 3).map(e => (
                  <div key={e.time} onClick={() => go(e.targetPage)} style={{ cursor: 'pointer' }}>
                    <span className={`event-dot ${e.color}`} />
                    <div><strong>{e.type}</strong><small>{e.detail}</small></div>
                    <time>{e.time}</time>
                  </div>
                ))}
              </div>
            </Section>
          </div>
          <div className="workspace-status">
            <div className="detail-label">NETWORK STATUS</div>
            <strong><span className="status-dot" /> Local network healthy</strong>
            <p>Devices · 2 routes · 24 ms median latency</p>
            <button className="ghost-btn" onClick={() => go('network')}>Inspect network <ChevronRight size={14} /></button>
          </div>
        </div>
      </div>
    </div>
  );
}

function TimelineHome({ go }: { go: (p: Page) => void }) {
  const [filter, setFilter] = useState<'all' | 'messages' | 'transfers' | 'routing'>('all');
  const filteredEvents = useMemo(() => {
    if (filter === 'all') return events;
    if (filter === 'messages') return events.filter(e => e.type.includes('Message'));
    if (filter === 'transfers') return events.filter(e => e.type.includes('File'));
    return events.filter(e => e.type.includes('Route') || e.type.includes('Device'));
  }, [filter]);

  return (
    <div className="timeline-layout">
      <div className="timeline-summary">
        <div>
          <span className="eyebrow">TODAY</span>
          <h2>A quiet network is a healthy network.</h2>
          <p>One stream for messages, devices, transfers and routes.</p>
        </div>
        <div className="summary-numbers">
          <span onClick={() => setFilter('all')} style={{ cursor: 'pointer' }}><strong>{events.length}</strong><small>events</small></span>
          <span onClick={() => go('nearby')} style={{ cursor: 'pointer' }}><strong>5</strong><small>devices</small></span>
          <span onClick={() => go('network')} style={{ cursor: 'pointer' }}><strong>2</strong><small>routes</small></span>
        </div>
      </div>
      <div className="timeline-filters">
        <button className={filter === 'all' ? 'active' : ''} onClick={() => setFilter('all')}>All activity</button>
        <button className={filter === 'messages' ? 'active' : ''} onClick={() => setFilter('messages')}>Messages</button>
        <button className={filter === 'transfers' ? 'active' : ''} onClick={() => setFilter('transfers')}>Transfers</button>
        <button className={filter === 'routing' ? 'active' : ''} onClick={() => setFilter('routing')}>Routing</button>
      </div>
      <div className="timeline-list">
        {filteredEvents.map(e => (
          <div className="timeline-event" key={e.time}>
            <time>{e.time}</time>
            <span className={`timeline-mark ${e.color}`}>
              {e.type.includes('Device') ? <Wifi size={15} /> : e.type.includes('File') ? <Download size={15} /> : e.type.includes('Route') ? <RefreshCw size={15} /> : <MessageCircle size={15} />}
            </span>
            <div>
              <strong>{e.type}</strong>
              <p>{e.detail}</p>
              <button className="text-btn" onClick={() => go(e.targetPage)}>
                View context <ChevronRight size={14} />
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function SheetHome({ go, setSheet }: { go: (p: Page) => void; setSheet: (s: string) => void }) {
  return (
    <div className="sheet-home">
      <div className="sheet-map" onClick={() => go('network')} style={{ cursor: 'pointer' }}>
        <span className="eyebrow">MESH OVERVIEW</span>
        <div className="sheet-topology">
          <div className="mini-node n1" /><div className="mini-node n2" /><div className="mini-node n3" /><div className="mini-node n4" />
          <span className="mini-line a" /><span className="mini-line b" /><span className="mini-line c" />
        </div>
        <div className="sheet-map-label">
          <span><i className="status-dot" /> Devices connected</span>
          <span>2 active routes</span>
        </div>
      </div>
      <div className="sheet-peek">
        <div className="grabber" />
        <span className="eyebrow">QUICK ACCESS</span>
        <h2>What do you want to do?</h2>
        <div className="sheet-actions">
          <SheetAction icon={<MessageCircle />} label="Chats" onClick={() => go('chats')} />
          <SheetAction icon={<ArrowUpFromLine />} label="Transfers" onClick={() => go('transfers')} />
          <SheetAction icon={<Radio />} label="Nearby" onClick={() => go('nearby')} />
          <SheetAction icon={<Globe2 />} label="Gateway" onClick={() => go('gateway')} />
        </div>
      </div>
    </div>
  );
}

function SheetAction({ icon, label, onClick }: { icon: React.ReactNode; label: string; onClick: () => void }) {
  return (
    <button onClick={onClick}>
      <span>{icon}</span>
      <strong>{label}</strong>
      <ChevronRight size={15} />
    </button>
  );
}

// -------------------------------------------------------------
// CHATS - Interactive Phase 5 Messaging & File Attachment
// -------------------------------------------------------------
function Chats({
  query, setQuery, contacts, selectedChatId, onSelectChat, messages, onSendMessage, notify, go
}: {
  query: string;
  setQuery: (s: string) => void;
  contacts: ChatContact[];
  selectedChatId: string;
  onSelectChat: (id: string) => void;
  messages: ChatMessage[];
  onSendMessage: (text: string, file?: { name: string; size: string; dataUrl?: string }) => void;
  notify: (s: string) => void;
  go: (p: Page) => void;
}) {
  const [inputText, setInputText] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const activeContact = contacts.find(c => c.id === selectedChatId) || contacts[0];

  function handleSend() {
    if (!inputText.trim()) return;
    onSendMessage(inputText);
    setInputText('');
  }

  function handleFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const formattedSize = file.size > 1024 * 1024
      ? `${(file.size / (1024 * 1024)).toFixed(1)} MB`
      : `${Math.round(file.size / 1024)} KB`;

    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      onSendMessage(`Sent file: ${file.name}`, {
        name: file.name,
        size: formattedSize,
        dataUrl,
      });
      notify(`Attached ${file.name}`);
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  }

  return (
    <div className="chat-workspace">
      <input
        type="file"
        ref={fileInputRef}
        style={{ display: 'none' }}
        onChange={handleFileSelected}
      />
      <aside className="chat-sidebar">
        <div className="searchbox">
          <Search size={16} />
          <input
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search conversations"
          />
        </div>
        <div className="chat-tabs">
          <button className="active">All <b>{contacts.length}</b></button>
          <button onClick={() => go('nearby')}>+ Find Peer</button>
        </div>
        {contacts.map(c => (
          <button
            key={c.id}
            className={`chat-select ${c.id === selectedChatId ? 'active' : ''}`}
            onClick={() => onSelectChat(c.id)}
          >
            <span className={`avatar avatar-${c.color}`}>{c.initials}</span>
            <span>
              <strong>{c.name}</strong>
              <small>{c.preview}</small>
            </span>
            <time>{c.time}</time>
          </button>
        ))}
      </aside>

      <div className="conversation">
        <div className="conversation-head">
          <div className="device-title">
            <span className={`avatar avatar-${activeContact.color}`}>{activeContact.initials}</span>
            <div>
              <h3>
                {activeContact.name} {activeContact.online && <i className="online-dot" />}
              </h3>
              <p>
                {activeContact.isMeshBroadcast
                  ? 'Mesh Broadcast Channel · Broadcast to all reachable devices'
                  : 'Connected via Bluetooth mesh · Route encrypted'}
              </p>
            </div>
          </div>
          <div>
            <button className="icon-btn" title="Inspect Peer Route" onClick={() => go('network')}>
              <NetworkIcon size={17} />
            </button>
            <button className="icon-btn" title="Nearby Peer Details" onClick={() => go('nearby')}>
              <Radio size={17} />
            </button>
          </div>
        </div>

        <div className="message-area">
          <div className="date-chip">ENCRYPTED LOCAL MESH ROUTE · NO INTERNET NEEDED</div>
          {messages.map(m => (
            <div key={m.id} className={`bubble ${m.sender === 'me' ? 'mine' : 'theirs'}`}>
              {m.senderName && <span className="sender-tag" style={{ display: 'block', fontSize: '11px', opacity: 0.7, marginBottom: '2px' }}>{m.senderName}</span>}
              {m.text && <div>{m.text}</div>}
              {m.file && (
                <div className="file-bubble" style={{ marginTop: m.text ? '8px' : '0' }}>
                  <span><FileText /></span>
                  <div>
                    <strong>{m.file.name}</strong>
                    <small>{m.file.size} · ready to open</small>
                  </div>
                  <button
                    title="Download / Open file"
                    onClick={() => {
                      triggerDownload(m.file!.name, m.file!.dataUrl || 'MESH-X local file content');
                      notify(`Downloaded ${m.file!.name}`);
                    }}
                  >
                    <Download size={15} />
                  </button>
                </div>
              )}
              <small>
                {m.time}
                {m.sender === 'me' && (
                  <span style={{ marginLeft: '4px' }}>
                    <Check size={11} />
                    {m.status === 'delivered' && <Check size={11} />}
                  </span>
                )}
              </small>
            </div>
          ))}
        </div>

        <div className="composer">
          <button
            className="icon-btn"
            title="Attach a file"
            onClick={() => fileInputRef.current?.click()}
          >
            <Paperclip size={18} />
          </button>
          <input
            placeholder={`Message ${activeContact.name}… (Press Enter to send)`}
            value={inputText}
            onChange={e => setInputText(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') handleSend(); }}
          />
          <button className="send-btn" title="Send" onClick={handleSend}>
            <Send size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}

function Network({
  selectedDevice, setSelectedDevice, notify, go
}: {
  selectedDevice: number; setSelectedDevice: (n: number) => void; notify: (s: string) => void; go: (p: Page) => void;
}) {
  return (
    <div className="network-page">
      <div className="network-headline">
        <div>
          <span className="eyebrow">INTERACTIVE TOPOLOGY</span>
          <h2>Every device has a path.</h2>
        </div>
        <div className="network-controls">
          <button className="ghost-btn" onClick={() => notify('Mesh routes recalculated')}>
            <RefreshCw size={15} /> Recalculate
          </button>
          <button className="icon-btn" onClick={() => go('nearby')} title="Nearby Devices">
            <Radio size={17} />
          </button>
        </div>
      </div>
      <div className="network-canvas">
        <div className="network-grid" />
        <div className="route-line r1" /><div className="route-line r2" /><div className="route-line r3" /><div className="route-line r4" />
        <MapNode x="center" label="You" icon={<Smartphone />} active selected={selectedDevice === 0} onClick={() => setSelectedDevice(0)} />
        <MapNode x="nw" label="Maya’s Laptop" icon={<Laptop />} selected={selectedDevice === 1} onClick={() => setSelectedDevice(1)} />
        <MapNode x="ne" label="Noah’s Tablet" icon={<TabletIcon />} selected={selectedDevice === 2} onClick={() => setSelectedDevice(2)} />
        <MapNode x="sw" label="Relay Node" icon={<Router />} selected={selectedDevice === 3} onClick={() => setSelectedDevice(3)} />
        <MapNode x="se" label="Gateway" icon={<Globe2 />} muted onClick={() => go('gateway')} />
        <div className="canvas-badge"><span className="status-dot" /> HEALTHY <small>98% confidence</small></div>
      </div>
      <div className="network-bottom">
        <div className="network-stat" onClick={() => notify('Route details updated')} style={{ cursor: 'pointer' }}>
          <span>Active routes</span><strong>2</strong><small>1 preferred · 1 alternative</small>
        </div>
        <div className="network-stat" onClick={() => go('debug')} style={{ cursor: 'pointer' }}>
          <span>Median latency</span><strong>24 ms</strong><small>Within expected range</small>
        </div>
        <div className="network-stat" onClick={() => go('gateway')} style={{ cursor: 'pointer' }}>
          <span>Gateway</span><strong className="warning-text">Offline</strong><small>Click to inspect gateway</small>
        </div>
      </div>
    </div>
  );
}

// -------------------------------------------------------------
// TRANSFERS - Interactive File Upload & Local Transfers
// -------------------------------------------------------------
function Transfers({ notify, go }: { notify: (s: string) => void; go: (p: Page) => void }) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [transfers, setTransfers] = useState([
    { id: 't1', icon: <Image />, name: 'site-photo-04.jpg', meta: '2.8 MB · Maya’s Laptop', status: 'Complete', dataUrl: '' },
    { id: 't2', icon: <FileText />, name: 'project-notes.pdf', meta: '1.4 MB · Alex Morgan', status: 'Ready', dataUrl: '' },
    { id: 't3', icon: <FileText />, name: 'route-log.txt', meta: '84 KB · Noah’s Tablet', status: 'Paused', dataUrl: '' },
  ]);

  function handleFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;

    const formattedSize = file.size > 1024 * 1024
      ? `${(file.size / (1024 * 1024)).toFixed(1)} MB`
      : `${Math.round(file.size / 1024)} KB`;

    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = reader.result as string;
      const newTransfer = {
        id: `tr_${Date.now()}`,
        icon: file.type.startsWith('image/') ? <Image /> : <FileText />,
        name: file.name,
        meta: `${formattedSize} · Field Team Mesh`,
        status: 'Complete',
        dataUrl,
      };
      setTransfers(prev => [newTransfer, ...prev]);
      notify(`Uploaded ${file.name} to mesh queue`);
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  }

  return (
    <div className="transfers-page">
      <input
        type="file"
        ref={fileInputRef}
        style={{ display: 'none' }}
        onChange={handleFileSelected}
      />
      <div className="transfer-summary">
        <div>
          <span className="eyebrow">TRANSFER MANAGER</span>
          <h2>Move files, not friction.</h2>
          <p>{transfers.length} items in local queue · local mesh route</p>
        </div>
        <button className="primary-btn" onClick={() => fileInputRef.current?.click()}>
          <Upload size={16} /> Add files
        </button>
      </div>

      <div className="transfer-progress">
        <div className="progress-head">
          <span><ArrowUpFromLine size={16} /> Mesh Packet Channel Active</span>
          <strong>100%</strong>
        </div>
        <div className="progress-track"><span style={{ width: '100%' }} /></div>
        <div className="progress-foot">
          <span>Direct P2P GATT Transfer Chunking</span>
          <span onClick={() => go('debug')} style={{ cursor: 'pointer' }}>Inspect packets →</span>
        </div>
      </div>

      <Section title="Queue">
        <div className="transfer-list">
          {transfers.map(t => (
            <TransferRow
              key={t.id}
              icon={t.icon}
              name={t.name}
              meta={t.meta}
              status={t.status}
              action={t.status === 'Paused' ? <Pause /> : <Download />}
              onAction={() => {
                triggerDownload(t.name, t.dataUrl || `MESH-X file: ${t.name}`);
                notify(`Downloading ${t.name}`);
              }}
            />
          ))}
        </div>
      </Section>

      <Section title="Transfer history" action={<button className="text-btn" onClick={() => notify('History cleared')}>Clear</button>}>
        <div className="history-row" onClick={() => notify('Opening transfer details')} style={{ cursor: 'pointer' }}>
          <span className="history-icon"><ArrowDownToLine /></span>
          <div>
            <strong>design-assets.zip</strong>
            <small>Received from Field Team · 12:04</small>
          </div>
          <b>84.2 MB</b>
        </div>
      </Section>
    </div>
  );
}

function TransferRow({
  icon, name, meta, status, action, onAction
}: {
  icon: React.ReactNode; name: string; meta: string; status: string; action: React.ReactNode; onAction?: () => void;
}) {
  return (
    <div className="transfer-row">
      <span className="file-icon">{icon}</span>
      <div><strong>{name}</strong><small>{meta}</small></div>
      <span className={`transfer-status ${status.toLowerCase()}`}>{status}</span>
      <button className="icon-btn" onClick={onAction}>{action}</button>
    </div>
  );
}

function Nearby({ notify, go }: { notify: (s: string) => void; go: (p: Page) => void }) {
  const isAndroid = isAndroidWebViewAvailable();
  const [caps, setCaps] = useState<DiscoveryCapabilitiesData | null>(null);
  const [peers, setPeers] = useState<NearbyDevice[]>([]);
  const [busy, setBusy] = useState(false);

  const syncDiscoveryState = useCallback(async () => {
    if (!isAndroid) { return; }
    const [capRes, listRes] = await Promise.all([getDiscoveryCapabilities(), getNearbyDevices()]);
    if (capRes.ok && capRes.data) { setCaps(capRes.data); }
    if (listRes.ok && listRes.data) { setPeers(listRes.data.devices); }
  }, [isAndroid]);

  useEffect(() => {
    if (!isAndroid) { return; }
    void syncDiscoveryState();
    return onBridgeEvent(evt => {
      if (['DEVICE_FOUND', 'DEVICE_LOST', 'DISCOVERY_STATE_CHANGED', 'BLUETOOTH_STATE_CHANGED', 'PEER_CONNECTION_CHANGED', 'BRIDGE_READY'].includes(evt.type)) {
        void syncDiscoveryState();
      }
    });
  }, [isAndroid, syncDiscoveryState]);

  async function toggleDiscovery() {
    if (!isAndroid) { notify('Discovery refreshed'); return; }
    setBusy(true);
    try {
      if (caps?.discoveryActive) {
        await stopDiscovery();
        notify('BLE discovery stopped');
      } else {
        const permRes = await requestBluetoothPermissions();
        if (!permRes.ok || !permRes.data?.granted) {
          notify('Bluetooth permission is required for nearby discovery');
          await syncDiscoveryState();
          return;
        }
        const startRes = await startDiscovery(false);
        if (!startRes.ok) {
          notify(startRes.error?.message ?? 'Unable to start discovery');
        } else {
          notify('BLE discovery active');
        }
      }
      await syncDiscoveryState();
    } finally {
      setBusy(false);
    }
  }

  async function handlePeerAction(peer: NearbyDevice) {
    if (peer.connectionState === 'connected' || peer.connectionState === 'connecting') {
      const res = await disconnectPeer(peer.address);
      notify(res.ok ? `Disconnected ${peer.name ?? peer.address}` : (res.error?.message ?? 'Disconnect failed'));
    } else {
      const res = await connectPeer(peer.address);
      notify(res.ok ? `Connecting to ${peer.name ?? peer.address}…` : (res.error?.message ?? 'Unable to connect'));
    }
    await syncDiscoveryState();
  }

  const statusHeadline = !isAndroid
    ? 'Looking for nearby peers'
    : caps?.discoveryActive
      ? 'BLE discovery active'
      : caps && !caps.bluetoothEnabled
        ? 'Bluetooth is turned off'
        : 'Ready for local discovery';

  const statusSub = !isAndroid
    ? 'Keep this screen open while devices announce themselves.'
    : caps?.discoveryActive
      ? 'Advertising your public fingerprint and scanning offline over Bluetooth LE.'
      : 'Enable Bluetooth and start discovery to find nearby MESH-X phones without internet.';

  return (
    <div className="nearby-page">
      <div className="discover-card">
        <div className="radar"><span /><i /><i /><i /></div>
        <div>
          <span className="eyebrow">{isAndroid ? (caps?.discoveryActive ? 'DISCOVERY ACTIVE' : 'STANDALONE BLE') : 'DISCOVERY ACTIVE'}</span>
          <h2>{statusHeadline}</h2>
          <p>{statusSub}</p>
        </div>
        <button className="ghost-btn" disabled={busy} onClick={() => void toggleDiscovery()}>
          <RefreshCw size={15} /> {isAndroid ? (caps?.discoveryActive ? 'Stop' : 'Start discovery') : 'Refresh'}
        </button>
      </div>

      <Section title="Discovered devices" action={<button className="text-btn" onClick={() => go('chats')}>Go to Chats <ChevronRight size={14} /></button>}>
        <div className="nearby-list">
          {!isAndroid ? (
            devices.slice(1).map((d, i) => (
              <div className="nearby-row" key={d.name}>
                <span className="device-icon">{d.icon}</span>
                <div>
                  <strong>{d.name}</strong>
                  <small>{d.kind} · seen {i + 1} min ago</small>
                </div>
                <span className="quality"><i className={i ? 'fair' : 'good'} />{d.quality}</span>
                <button className="ghost-btn" onClick={() => go('chats')}>Message</button>
              </div>
            ))
          ) : peers.length === 0 ? (
            <div className="empty-state">
              <Bluetooth size={28} />
              <strong>No MESH-X devices discovered yet</strong>
              <p>Start discovery on two nearby Android phones with Bluetooth enabled.</p>
            </div>
          ) : (
            peers.map(peer => (
              <div className="nearby-row" key={peer.address}>
                <span className="device-icon"><Smartphone size={18} /></span>
                <div>
                  <strong>{peer.name || 'MESH-X Peer'}{peer.fingerprint ? ` · ${peer.fingerprint}` : ''}</strong>
                  <small>{peer.connectionState === 'connected' ? 'Connected & identity verified' : peer.connectionState === 'connecting' ? 'Connecting via GATT…' : `RSSI ${peer.rssi} dBm`}</small>
                </div>
                <span className="quality"><i className={peer.rssi > -72 ? 'good' : 'fair'} />{peer.rssi} dBm</span>
                <button className="ghost-btn" onClick={() => void handlePeerAction(peer)}>
                  {peer.connectionState === 'connected' ? 'Disconnect' : peer.connectionState === 'connecting' ? 'Cancel' : 'Connect'}
                </button>
              </div>
            ))
          )}
        </div>
      </Section>

      <div className="privacy-note">
        <Shield size={18} />
        <span>
          <strong>Identity stays in your control</strong>
          <small>Only your public-key fingerprint and device ID are shared over BLE GATT. Private keys never leave Android Keystore.</small>
        </span>
      </div>
    </div>
  );
}

function Gateway({ notify, go }: { notify: (s: string) => void; go: (p: Page) => void }) {
  return (
    <div className="gateway-page">
      <div className="gateway-hero">
        <span className="gateway-icon"><Globe2 /></span>
        <div>
          <span className="eyebrow">INTERNET ACCESS</span>
          <h2>No gateway available</h2>
          <p>Your mesh is healthy. Internet routing is a separate, optional layer.</p>
        </div>
        <span className="offline-tag">OFFLINE</span>
      </div>
      <div className="gateway-grid">
        <div className="gateway-card">
          <div className="gateway-card-head"><Router size={18} /><span>Available gateways</span></div>
          <div className="empty-state">
            <Wifi size={28} />
            <strong>No approved gateways</strong>
            <p>Ask a nearby peer to share internet access, or continue using local-only communication.</p>
            <button className="primary-btn" onClick={() => notify('Gateway request simulated')}>
              <Send size={15} /> Request access
            </button>
          </div>
        </div>
        <div className="gateway-card">
          <div className="gateway-card-head"><Activity size={18} /><span>Usage this session</span></div>
          <div className="usage">
            <div><strong>0 B</strong><small>Internet data</small></div>
            <div><strong>100%</strong><small>Local traffic</small></div>
            <div className="usage-bar"><span style={{ width: '100%' }} /></div>
          </div>
          <div className="gateway-note">
            <Info size={15} /> Offline local communication is fully functional.
          </div>
        </div>
      </div>
    </div>
  );
}

function formatPublicKeyPreview(publicKey: string) {
  return publicKey.length > 28 ? `${publicKey.slice(0, 14)}…${publicKey.slice(-10)}` : publicKey;
}

// -------------------------------------------------------------
// SETTINGS - Full Interactive Sub-Tabs Navigation
// -------------------------------------------------------------
function Settings({
  notify, identity, identityStatus, identityError, onRefreshIdentity, go
}: {
  notify: (s: string) => void;
  identity: DeviceIdentityData | null;
  identityStatus: IdentityStatus;
  identityError: string | null;
  onRefreshIdentity: () => void;
  go: (p: Page) => void;
}) {
  const isAndroid = isAndroidWebViewAvailable();
  const [activeTab, setActiveTab] = useState<'identity' | 'privacy' | 'connectivity' | 'notifications' | 'storage' | 'appearance'>('identity');
  const [bgStatus, setBgStatus] = useState<BackgroundStatusData | null>(null);
  const [storageStats, setStorageStats] = useState<LocalStorageStatsData | null>(null);

  const syncSettingsState = useCallback(async () => {
    if (!isAndroid) { return; }
    const [bgRes, statsRes] = await Promise.all([getBackgroundStatus(), getLocalStorageStats()]);
    if (bgRes.ok && bgRes.data) { setBgStatus(bgRes.data); }
    if (statsRes.ok && statsRes.data) { setStorageStats(statsRes.data); }
  }, [isAndroid]);

  useEffect(() => {
    if (!isAndroid) { return; }
    void syncSettingsState();
    return onBridgeEvent(evt => {
      if (['BACKGROUND_STATE_CHANGED', 'DISCOVERY_STATE_CHANGED', 'PEER_CONNECTION_CHANGED', 'BRIDGE_READY'].includes(evt.type)) {
        void syncSettingsState();
      }
    });
  }, [isAndroid, syncSettingsState]);

  async function toggleBackgroundService() {
    if (!isAndroid) { notify('Background service requires the Android app'); return; }
    if (bgStatus?.running) {
      const res = await stopBackgroundService();
      notify(res.ok ? 'Background discovery stopped' : (res.error?.message ?? 'Unable to stop background service'));
    } else {
      const permRes = await requestBluetoothPermissions();
      if (!permRes.ok || !permRes.data?.granted) {
        notify('Grant Bluetooth permissions before enabling background discovery');
        return;
      }
      const res = await startBackgroundService();
      notify(res.ok ? 'Background service start requested' : (res.error?.message ?? 'Unable to start background service'));
    }
    await syncSettingsState();
  }

  const identityTitle = identityStatus === 'ready'
    ? 'This Android device'
    : identityStatus === 'loading'
      ? 'Loading device identity…'
      : identityStatus === 'unavailable'
        ? 'Browser preview mode'
        : 'Device identity unavailable';

  const identityDetail = identityStatus === 'ready' && identity
    ? `ID ${identity.deviceId} · ${identity.algorithm} (P-256) · ${formatPublicKeyPreview(identity.publicKey)}`
    : identityStatus === 'loading'
      ? 'Querying Android Keystore via native bridge'
      : identityStatus === 'unavailable'
        ? 'Android Keystore identity requires the Android app shell'
        : identityError ?? 'Unable to load device identity';

  const avatarText = identityStatus === 'ready' ? 'MX' : identityStatus === 'unavailable' ? 'WP' : 'ID';

  return (
    <div className="settings-page">
      <div className="settings-nav">
        <button className={activeTab === 'identity' ? 'active' : ''} onClick={() => setActiveTab('identity')}>Device identity</button>
        <button className={activeTab === 'privacy' ? 'active' : ''} onClick={() => setActiveTab('privacy')}>Privacy & security</button>
        <button className={activeTab === 'connectivity' ? 'active' : ''} onClick={() => setActiveTab('connectivity')}>Connectivity</button>
        <button className={activeTab === 'notifications' ? 'active' : ''} onClick={() => setActiveTab('notifications')}>Notifications</button>
        <button className={activeTab === 'storage' ? 'active' : ''} onClick={() => setActiveTab('storage')}>Storage</button>
        <button className={activeTab === 'appearance' ? 'active' : ''} onClick={() => setActiveTab('appearance')}>Appearance</button>
      </div>

      <div className="settings-panel">
        {activeTab === 'identity' && (
          <>
            <div className="settings-section">
              <span className="eyebrow">DEVICE IDENTITY</span>
              <h2>How peers recognize you.</h2>
              <div className="identity-card">
                <span className="avatar avatar-lavender big">{avatarText}</span>
                <div>
                  <strong>{identityTitle}</strong>
                  <small>{identityDetail}</small>
                </div>
                <button className="ghost-btn" onClick={onRefreshIdentity}>Refresh</button>
              </div>
            </div>

            <div className="settings-section">
              <span className="eyebrow">PREFERENCES</span>
              <div className="setting-row">
                <div>
                  <strong>Background BLE service</strong>
                  <small>
                    {isAndroid
                      ? (bgStatus?.running
                        ? `Foreground service running · BLE scan ${bgStatus.scanning ? 'active' : 'inactive'} · advertising ${bgStatus.advertising ? 'active' : 'inactive'}.`
                        : 'Keep local discovery running when minimized; Android & OEM battery savers may still pause idle apps.')
                      : 'Available when running on Android.'}
                  </small>
                </div>
                <button className={`toggle ${bgStatus?.running ? 'on' : ''}`} onClick={() => void toggleBackgroundService()}>
                  <span />
                </button>
              </div>
              <SettingRow title="Auto-accept trusted peers" sub="Only peers you have approved can connect automatically." />
              <SettingRow title="Show route details" sub="Display hop count and route quality in conversations." on />
            </div>

            <div className="settings-section">
              <span className="eyebrow">OFFLINE STORAGE & SECURITY</span>
              <div className="security-note">
                <Shield />
                <span>
                  <strong>Offline-first local mesh</strong>
                  <small>
                    {storageStats ? `SQLite store: ${storageStats.peerCount} verified peers · ${storageStats.messageCount} messages · ${storageStats.relayQueueCount} relay packets. ` : ''}
                    Private key stays inside Android Keystore. Internet is optional; MESH-X never shares your mobile data.
                  </small>
                </span>
                <Check size={16} />
              </div>
            </div>
          </>
        )}

        {activeTab === 'privacy' && (
          <div className="settings-section">
            <span className="eyebrow">PRIVACY & SECURITY</span>
            <h2>Zero-Trust Cryptographic Identity</h2>
            <SettingRow title="Hardware-backed Keystore" sub="Keys are bound to this hardware and cannot be exported." on />
            <SettingRow title="Anonymous BLE Advertising" sub="Rotate BLE address dynamically where supported by manufacturer." on />
            <SettingRow title="Auto-Purge Expired Packets" sub="Relay packets older than 24 hours are deleted automatically." on />
          </div>
        )}

        {activeTab === 'connectivity' && (
          <div className="settings-section">
            <span className="eyebrow">CONNECTIVITY</span>
            <h2>Bluetooth LE & Mesh Radios</h2>
            <SettingRow title="High-Speed GATT Handshake" sub="Exchange full public keys immediately upon connection." on />
            <SettingRow title="Low-Power Scan Mode" sub="Reduce battery consumption while still receiving nearby broadcasts." />
            <div className="setting-row">
              <div>
                <strong>Discovery Controls</strong>
                <small>Quickly switch to nearby discovery screen.</small>
              </div>
              <button className="ghost-btn" onClick={() => go('nearby')}>Open Nearby</button>
            </div>
          </div>
        )}

        {activeTab === 'notifications' && (
          <div className="settings-section">
            <span className="eyebrow">NOTIFICATIONS</span>
            <h2>Local In-App Alerts</h2>
            <SettingRow title="New message alerts" sub="Show banner when message arrives over mesh." on />
            <SettingRow title="Peer discovery alerts" sub="Notify when new verified peer is within range." on />
          </div>
        )}

        {activeTab === 'storage' && (
          <div className="settings-section">
            <span className="eyebrow">OFFLINE STORAGE</span>
            <h2>Local Database Stats</h2>
            <div className="command-stats" style={{ marginTop: '12px' }}>
              <div><span className="stat-icon cyan"><Users /></span><strong>{storageStats?.peerCount ?? 3}</strong><small>peers</small></div>
              <div><span className="stat-icon violet"><MessageCircle /></span><strong>{storageStats?.messageCount ?? 12}</strong><small>messages</small></div>
              <div><span className="stat-icon green"><Router /></span><strong>{storageStats?.relayQueueCount ?? 0}</strong><small>queued packets</small></div>
            </div>
          </div>
        )}

        {activeTab === 'appearance' && (
          <div className="settings-section">
            <span className="eyebrow">APPEARANCE</span>
            <h2>Theme & UI Configuration</h2>
            <SettingRow title="Ambient Starfield" sub="Enable subtle cosmic background animation." on />
            <SettingRow title="Compact Message Bubbles" sub="Increase chat density for small screens." />
          </div>
        )}
      </div>
    </div>
  );
}

function SettingRow({ title, sub, on }: { title: string; sub: string; on?: boolean }) {
  const [checked, setChecked] = useState(!!on);
  return (
    <div className="setting-row">
      <div><strong>{title}</strong><small>{sub}</small></div>
      <button className={`toggle ${checked ? 'on' : ''}`} onClick={() => setChecked(!checked)}>
        <span />
      </button>
    </div>
  );
}

function Debug({ go, notify }: { go: (p: Page) => void; notify: (s: string) => void }) {
  return (
    <div className="debug-page">
      <div className="debug-toolbar">
        <div>
          <span className="eyebrow">PACKET MONITOR / LIVE TRACE</span>
          <h2>Under the hood.</h2>
        </div>
        <div className="debug-pills">
          <span><i className="status-dot" /> Capturing</span>
          <span>Mesh Relay Active</span>
          <button className="ghost-btn" onClick={() => notify('Debug log snapshot saved')}>Export</button>
        </div>
      </div>
      <div className="log-table">
        <div className="log-head">
          <span>TIME</span><span>EVENT</span><span>PACKET ID</span><span>PATH</span><span>STATUS</span>
        </div>
        {['10:42:18', '10:42:17', '10:42:15', '10:42:12', '10:42:04', '10:41:58'].map((time, i) => (
          <div className="log-row" key={time}>
            <time>{time}</time>
            <strong>{['ROUTE_UPDATE', 'PACKET_FORWARD', 'MESSAGE_DELIVERED', 'DEVICE_HEARTBEAT', 'ROUTE_PROBE', 'GATEWAY_CHECK'][i]}</strong>
            <code>mx_{['8f21', '8f20', '8f1d', '8f1a', '8f19', '8f16'][i]}</code>
            <span className="path">{i % 2 ? 'You → Maya → Noah' : 'You → Maya'}</span>
            <b className={i === 5 ? 'warn' : ''}>{i === 5 ? 'NO_ROUTE' : 'OK'}</b>
          </div>
        ))}
      </div>
    </div>
  );
}

function BottomSheet({
  title, close, go, notify
}: {
  title: string; close: () => void; go: (p: Page) => void; notify: (s: string) => void;
}) {
  return (
    <div className="sheet-backdrop" onClick={close}>
      <div className="bottom-sheet" onClick={e => e.stopPropagation()}>
        <div className="grabber" />
        <div className="sheet-head">
          <div><span className="eyebrow">PANEL</span><h2>{title}</h2></div>
          <button className="icon-btn" onClick={close}><X size={18} /></button>
        </div>
        {title === 'Transfers' ? (
          <>
            <TransferRow icon={<Image />} name="photos.zip" meta="Uploading · 68%" status="Active" action={<Pause />} />
            <TransferRow icon={<FileText />} name="project-notes.pdf" meta="Ready to download" status="Ready" action={<Download />} />
            <button className="primary-btn full" onClick={() => { close(); go('transfers'); }}>
              Open full Transfers screen
            </button>
          </>
        ) : title === 'Nearby devices' ? (
          <>
            <ActionRow icon={<Laptop />} title="Maya’s Laptop" sub="Good signal · 1 hop" onClick={() => { close(); go('chats'); }} />
            <ActionRow icon={<TabletIcon />} title="Noah’s Tablet" sub="Fair signal · 2 hops" onClick={() => { close(); go('chats'); }} />
            <button className="primary-btn full" onClick={() => { close(); go('nearby'); }}>
              Open Nearby Discovery
            </button>
          </>
        ) : title === 'Quick Actions' ? (
          <div className="action-list" style={{ marginTop: '10px' }}>
            <ActionRow icon={<MessageCircle />} title="Send a Message" sub="Open mesh chat" onClick={() => { close(); go('chats'); }} />
            <ActionRow icon={<Upload />} title="Upload File" sub="Share photo, doc, or file" onClick={() => { close(); go('transfers'); }} />
            <ActionRow icon={<Radio />} title="Discover Devices" sub="Scan nearby BLE peers" onClick={() => { close(); go('nearby'); }} />
          </div>
        ) : (
          <>
            <div className="gateway-mini">
              <Globe2 />
              <div><strong>No gateway available</strong><small>Local mesh communication is active.</small></div>
            </div>
            <button className="primary-btn full" onClick={() => { close(); go('gateway'); }}>
              Open Gateway Settings
            </button>
          </>
        )}
      </div>
    </div>
  );
}

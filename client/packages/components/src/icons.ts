// The icon vocabulary of the UI (corporate-design.md §5): one meaning, one
// icon. Keys name what an icon MEANS, values pick the Lucide glyph — so a view
// asks for `delete`, never for `Trash2`, and swapping a glyph is a one-line
// change here. Only the icons listed here end up in a bundle (Lucide is
// tree-shaken per named import).
//
// Need a new meaning? Add it here, and to the table in corporate-design §5 when
// it is a general one. Emojis and Unicode arrows are not icons (§5).

import type { Component } from 'vue';
import {
  AppWindow, Archive, ArchiveRestore, ArrowDown, ArrowLeft, ArrowLeftRight, ArrowRight, ArrowUp,
  ArrowUpDown, Bell, Bot, Calendar, Check, ChevronDown, ChevronLeft, ChevronRight, ChevronUp,
  CircleCheck, CircleHelp, CircleX, Code, Copy, Crop, Download, Ellipsis, ExternalLink, Eye, EyeOff,
  File, FileText, Folder, FolderOpen, FolderPlus, Globe, GripVertical, History, House, Image, Inbox,
  Info, KeyRound, LayoutGrid, Link, List, ListFilter, Loader, Lock, LockOpen, LogOut, MapPin,
  Maximize2, Menu, MessageSquare, Mic, Minimize2, Minus, Moon, Package, PanelLeft, PanelRight,
  Paperclip, Palette, Pause, Pencil, Pin, Play, Plus, Redo2, RotateCw, Save, Search, Send, Settings,
  Share, Sparkles, Square, SquareKanban, Star, Sun, Table, Tag, Terminal, TriangleAlert, Trash2,
  Undo2, Upload, User, Users, Volume2, Wrench, X,
} from 'lucide-vue-next';

export const ICONS = {
  // actions
  'add': Plus,
  'add-folder': FolderPlus,
  'delete': Trash2,
  'edit': Pencil,
  'close': X,
  'refresh': RotateCw,
  'search': Search,
  'filter': ListFilter,
  'sort': ArrowUpDown,
  'download': Download,
  'upload': Upload,
  'share': Share,
  'copy': Copy,
  'save': Save,
  'undo': Undo2,
  'redo': Redo2,
  'crop': Crop,
  'more': Ellipsis,
  'menu': Menu,
  'drag': GripVertical,
  'send': Send,
  'attach': Paperclip,
  'voice': Mic,
  'play': Play,
  'pause': Pause,
  'stop': Square,
  'show': Eye,
  'hide': EyeOff,
  'pin': Pin,
  'star': Star,
  'archive': Archive,
  'restore': ArchiveRestore,
  'external': ExternalLink,
  'link': Link,
  'open-in-app': AppWindow,
  'maximize': Maximize2,
  'minimize': Minimize2,
  'sign-out': LogOut,

  // navigation
  'back': ChevronLeft,
  'forward': ChevronRight,
  'expand': ChevronDown,
  'collapse': ChevronUp,
  'arrow-left': ArrowLeft,
  'arrow-right': ArrowRight,
  'arrow-up': ArrowUp,
  'arrow-down': ArrowDown,
  'swap': ArrowLeftRight,
  'home': House,
  'sidebar': PanelLeft,
  'panel-right': PanelRight,

  // places and things
  'folder': Folder,
  'folder-open': FolderOpen,
  'document': FileText,
  'file': File,
  'image': Image,
  'code': Code,
  'table': Table,
  'kanban': SquareKanban,
  'grid': LayoutGrid,
  'list': List,
  'calendar': Calendar,
  'chat': MessageSquare,
  'inbox': Inbox,
  'notification': Bell,
  'history': History,
  'kit': Package,
  'tag': Tag,
  'location': MapPin,
  'terminal': Terminal,
  'tool': Wrench,
  'agent': Bot,
  'ai': Sparkles,
  'user': User,
  'team': Users,
  'key': KeyRound,
  'lock': Lock,
  'unlock': LockOpen,
  'language': Globe,
  'theme': Palette,
  'volume': Volume2,
  'settings': Settings,
  'help': CircleHelp,
  'light': Sun,
  'dark': Moon,

  // states
  'check': Check,
  'success': CircleCheck,
  'info': Info,
  'warning': TriangleAlert,
  'error': CircleX,
  'loading': Loader,
  'minus': Minus,
} as const satisfies Record<string, Component>;

export type IconName = keyof typeof ICONS;

export function isIconName(value: string): value is IconName {
  return Object.prototype.hasOwnProperty.call(ICONS, value);
}

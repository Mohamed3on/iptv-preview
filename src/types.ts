export interface Channel {
  name: string;
  url: string;
  logo?: string;
  group?: string;
  tvgId?: string;
  tvgName?: string;
}

export interface Program {
  channel: string;
  title: string;
  description?: string;
  start: number; // epoch ms
  stop: number;
}

export interface EPGData {
  programs: Record<string, Program[]>;
  /** maps lowercase display-name / alias -> canonical channel id */
  aliases: Record<string, string>;
}

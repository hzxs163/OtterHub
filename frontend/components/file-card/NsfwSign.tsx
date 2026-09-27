import { AlertTriangle } from "lucide-react";

export function NsfwSign() {
  return (
    <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
      <div className="flex flex-col items-center gap-2 bg-secondary/80 backdrop-blur-sm px-4 py-3 rounded-xl border border-glass-border">
        <AlertTriangle className="h-8 w-8 text-amber-400" />
        <span className="text-sm font-medium text-amber-500">不适宜内容</span>
        <span className="text-xs text-foreground/60">安全模式已开启</span>
      </div>
    </div>
  )
}
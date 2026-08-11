import * as React from 'react'
import { cn } from '@/lib/utils'

export function Input({ className, type, ...props }: React.ComponentProps<'input'>) {
  return (
    <input
      type={type}
      className={cn(
        // Girdi kutusu zeminden bir tık KOYU (bg-input): kart üstünde saydam
        // bırakılınca kutunun nerede başladığı yalnızca ince çerçeveden
        // anlaşılıyordu.
        'flex h-10 w-full rounded-lg border bg-input-bg px-3.5 py-1 text-sm transition-colors',
        'placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]',
        'disabled:cursor-not-allowed disabled:text-text-disabled disabled:opacity-70',
        className,
      )}
      {...props}
    />
  )
}

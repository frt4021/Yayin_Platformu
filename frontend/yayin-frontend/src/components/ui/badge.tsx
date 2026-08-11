import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const badgeVariants = cva(
  // Tam yuvarlak: tasarımda rozetler hap biçiminde ("1/16" sayacı, rol
  // etiketi). Köşeli rozet düğmelerle karışıyordu.
  'inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium w-fit whitespace-nowrap',
  {
    variants: {
      variant: {
        default: 'border-transparent bg-primary text-primary-foreground',
        secondary: 'border-transparent bg-secondary text-secondary-foreground',
        destructive: 'border-transparent bg-destructive text-destructive-foreground',
        outline: 'text-muted-foreground',

        // Yayın durumu rozetleri: dolu renk yerine %15 opaklıkta zemin +
        // renkli yazı. Koyu arayüzde dolu kırmızı/yeşil bir rozet göz
        // hizasında bağırıyor; tabloda onlarca satır olduğunda okunaksızlaşır.
        live: 'border-transparent bg-status-live-bg text-status-live',
        success: 'border-transparent bg-status-success-bg text-status-success',
        warning: 'border-transparent bg-status-warning-bg text-status-warning',
        error: 'border-transparent bg-status-error-bg text-status-error',

        /**
         * Kullanıcı rolü — tasarımda üst çubuğun sağ ucundaki nane hap.
         *
         * Diğer rozetlerin aksine DOLU renk: bu, arayüzdeki tek doygun
         * vurgu ve bilerek göze çarpıyor. Saydam zeminli olsaydı yanındaki
         * kullanıcı adıyla aynı ağırlıkta okunurdu.
         */
        role: 'border-transparent bg-primary text-primary-foreground',
      },
    },
    defaultVariants: { variant: 'default' },
  },
)

export function Badge({
  className,
  variant,
  ...props
}: React.ComponentProps<'span'> & VariantProps<typeof badgeVariants>) {
  return <span className={cn(badgeVariants({ variant }), className)} {...props} />
}

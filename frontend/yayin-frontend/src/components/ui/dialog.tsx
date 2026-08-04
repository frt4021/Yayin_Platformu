import * as React from 'react'
import * as DialogPrimitive from '@radix-ui/react-dialog'
import { XIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

export const Dialog = DialogPrimitive.Root
export const DialogTrigger = DialogPrimitive.Trigger
export const DialogClose = DialogPrimitive.Close

export function DialogContent({
  className,
  children,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content>) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/60" />
      <DialogPrimitive.Content
        className={cn(
          'fixed left-1/2 top-1/2 z-50 flex w-full max-w-lg -translate-x-1/2 -translate-y-1/2 flex-col gap-4',
          'border bg-card p-6 shadow-lg rounded-xl',
          // Ekrana sigmayan icerik: dialog sabit konumlu oldugu icin sayfa
          // kaydirilamaz — sinir konmazsa formun ustu ve alti (Kaydet dahil)
          // erisilemez hale gelir. Govde kaydirmasi icin DialogBody kullanin;
          // buradaki overflow yalnizca onu kullanmayan dialoglar icin emniyet.
          'max-h-[calc(100dvh-2rem)] overflow-y-auto',
          className,
        )}
        {...props}
      >
        {children}
        <DialogPrimitive.Close className="absolute right-4 top-4 opacity-70 hover:opacity-100">
          <XIcon className="size-4" />
          <span className="sr-only">Kapat</span>
        </DialogPrimitive.Close>
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  )
}

export function DialogHeader({ className, ...props }: React.ComponentProps<'div'>) {
  return <div className={cn('flex flex-col gap-1.5 text-left', className)} {...props} />
}

/**
 * Uzun dialoglarda kaydırılan bölüm. Başlık ve düğmeler dışarıda kalır;
 * yalnızca gövde kayar, böylece Kaydet her zaman görünür durumda olur.
 *
 * <p>Negatif yatay kenar boşluğu, kaydırma çubuğunu {@code DialogContent}
 * dolgusunun içine değil kenarına taşıyor.
 */
export function DialogBody({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div className={cn('-mx-6 min-h-0 flex-1 overflow-y-auto px-6', className)} {...props} />
  )
}

export function DialogFooter({ className, ...props }: React.ComponentProps<'div'>) {
  return <div className={cn('flex flex-row justify-end gap-2', className)} {...props} />
}

export function DialogTitle({
  className,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Title>) {
  return (
    <DialogPrimitive.Title
      className={cn('text-lg font-semibold leading-none', className)}
      {...props}
    />
  )
}

export function DialogDescription({
  className,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Description>) {
  return (
    <DialogPrimitive.Description
      className={cn('text-sm text-muted-foreground', className)}
      {...props}
    />
  )
}

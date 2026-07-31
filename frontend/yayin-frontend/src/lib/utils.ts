import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * shadcn/ui bileşenlerinin standart sınıf birleştiricisi.
 * clsx koşullu sınıfları çözer, twMerge çakışan Tailwind sınıflarından
 * sonuncuyu kazandırır (ör. "p-2 p-4" -> "p-4").
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

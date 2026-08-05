/**
 * Dosyayı imzalı adrese doğrudan yükler.
 *
 * <p><b>Neden {@code api} istemcisi kullanılmıyor:</b> hedef backend değil,
 * nesne depolaması. İsteğe {@code Authorization} başlığı eklenirse MinIO onu
 * kendi kimlik doğrulaması sanıp imzalı adresi reddeder — dosya backend'e de
 * gitmediği için oradaki oturumun bir anlamı yok zaten.
 *
 * <p><b>Neden {@code fetch} değil XHR:</b> fetch yükleme ilerlemesi
 * bildirmiyor. 4 GB'lık bir dosyayı ilerleme göstergesi olmadan yüklemek
 * kullanıcıya "dondu" hissi verir.
 */
export interface UploadHandle {
  /** Yükleme tamamlandığında çözülür; iptal veya hata halinde reddedilir. */
  done: Promise<void>
  /** Kullanıcı vazgeçtiğinde çağrılır. */
  abort: () => void
}

export class UploadError extends Error {
  /** MinIO'nun döndüğü HTTP kodu; ağ hatasında 0. */
  readonly status: number

  // Kurucu parametresi olarak yazılamıyor: tsconfig'de erasableSyntaxOnly
  // açık, yani TypeScript'e özgü "parameter property" kısayolu yasak.
  constructor(message: string, status: number) {
    super(message)
    this.name = 'UploadError'
    this.status = status
  }
}

export function uploadToStorage(
  url: string,
  file: File,
  contentType: string | null,
  onProgress: (loaded: number, total: number) => void,
): UploadHandle {
  const xhr = new XMLHttpRequest()

  const done = new Promise<void>((resolve, reject) => {
    xhr.open('PUT', url, true)

    // İmzada content-type YOK (backend bilerek imzalamıyor), ama nesnenin
    // doğru tiple kaydedilmesi için yine de gönderiliyor: yanlış tiple
    // kaydedilen bir video tarayıcıda oynatılamayabilir.
    const type = contentType || file.type
    if (type) {
      xhr.setRequestHeader('Content-Type', type)
    }

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress(event.loaded, event.total)
      }
    }

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }
      // MinIO hata gövdesini XML olarak döner; ilk <Message> yeterince açıklayıcı.
      const detail = /<Message>([^<]+)<\/Message>/.exec(xhr.responseText)?.[1]
      reject(
        new UploadError(
          detail ?? `Depolama yüklemeyi reddetti (HTTP ${xhr.status}).`,
          xhr.status,
        ),
      )
    }

    xhr.onerror = () =>
      reject(new UploadError('Yükleme sırasında bağlantı koptu.', 0))

    xhr.onabort = () => reject(new UploadError('Yükleme iptal edildi.', 0))

    xhr.send(file)
  })

  return { done, abort: () => xhr.abort() }
}

/** {@code 1.4 GB}, {@code 812 MB} … */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(0)} MB`
  return `${(bytes / 1024).toFixed(0)} KB`
}

/** Saniyeyi {@code 1:23:45} veya {@code 4:07} biçimine çevirir. */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

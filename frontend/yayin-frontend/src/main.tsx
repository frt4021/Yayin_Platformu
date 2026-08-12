import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { ayarlariYukle } from './player/oynaticiAyarlari'

// Oynatıcı ayarları uygulamadan ÖNCE alınıyor: hls.js kurulurken okunuyorlar
// ve kurulum bir kez oluyor. Sonradan gelseydi ilk oynatıcı yanlış değerle
// kurulur, düzeltmek için yeniden kurmak gerekirdi — canlı yayında görünür
// bir kesinti.
//
// Beklemek açılışı geciktirmiyor: uç kimlik istemiyor ve tek bir sayı
// dönüyor. Hata durumunda da bekleme bitiyor, varsayılanlarla devam.
void ayarlariYukle().finally(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
})


import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Toaster } from 'sonner'
import { AuthProvider } from '@/auth/AuthContext'
import { RequireAuth } from '@/auth/RequireAuth'
import { AppLayout } from '@/components/AppLayout'
import { AdminLayout } from '@/components/AdminLayout'
import { LoginPage } from '@/pages/LoginPage'
import { ProfilePage } from '@/pages/ProfilePage'
import { ChannelsPage } from '@/pages/ChannelsPage'
import { RadiosPage } from '@/pages/RadiosPage'
import { VideosPage } from '@/pages/VideosPage'
import { GaleriPage } from '@/pages/GaleriPage'
import { WatchPage } from '@/pages/WatchPage'
import { DvrPage } from '@/pages/dvr/DvrPage'
import { ClipsPage } from '@/pages/ClipsPage'
import { UnauthorizedPage } from '@/pages/UnauthorizedPage'
import { AdminGenelBakisPage } from '@/pages/admin/AdminGenelBakisPage'
import { AdminUsersPage } from '@/pages/admin/AdminUsersPage'
import { AdminEtkinliklerPage } from '@/pages/admin/AdminEtkinliklerPage'
import { AdminAnalitikPage } from '@/pages/admin/AdminAnalitikPage'
import { AdminSistemLoglarPage } from '@/pages/admin/AdminSistemLoglarPage'

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/giris" element={<LoginPage />} />
          <Route path="/yetkisiz" element={<UnauthorizedPage />} />

          {/* Giriş yapmış herkes */}
          <Route element={<RequireAuth />}>
            <Route element={<AppLayout />}>
              <Route index element={<Navigate to="/izle" replace />} />
              <Route path="/izle" element={<WatchPage />} />
              <Route path="/kanallar" element={<ChannelsPage />} />
              <Route path="/radyolar" element={<RadiosPage />} />
              <Route path="/geriye-sarma" element={<DvrPage />} />
              <Route path="/klipler" element={<ClipsPage />} />
              <Route path="/videolar" element={<VideosPage />} />
              <Route path="/galeri" element={<GaleriPage />} />
              <Route path="/profil" element={<ProfilePage />} />
            </Route>

            {/* Yönetim paneli — izleme uygulamasının kabuğundan (mozaik
                oynatıcılar, sol çubuk) tamamen ayrı, kendi kabuğuyla. */}
            <Route element={<RequireAuth roles={['Yönetici']} />}>
              <Route element={<AdminLayout />}>
                <Route path="/yonetim" element={<Navigate to="/yonetim/genel-bakis" replace />} />
                <Route path="/yonetim/genel-bakis" element={<AdminGenelBakisPage />} />
                <Route path="/yonetim/kullanicilar" element={<AdminUsersPage />} />
                <Route path="/yonetim/etkinlikler" element={<AdminEtkinliklerPage />} />
                <Route path="/yonetim/analitik" element={<AdminAnalitikPage />} />
                <Route path="/yonetim/sistem-loglari" element={<AdminSistemLoglarPage />} />
              </Route>
            </Route>
          </Route>

          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
      <Toaster theme="dark" position="top-right" richColors />
    </AuthProvider>
  )
}

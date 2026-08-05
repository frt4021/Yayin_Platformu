
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { Toaster } from 'sonner'
import { AuthProvider } from '@/auth/AuthContext'
import { RequireAuth } from '@/auth/RequireAuth'
import { AppLayout } from '@/components/AppLayout'
import { LoginPage } from '@/pages/LoginPage'
import { ProfilePage } from '@/pages/ProfilePage'
import { ChannelsPage } from '@/pages/ChannelsPage'
import { RadiosPage } from '@/pages/RadiosPage'
import { VideosPage } from '@/pages/VideosPage'
import { WatchPage } from '@/pages/WatchPage'
import { DvrPage } from '@/pages/dvr/DvrPage'
import { ClipsPage } from '@/pages/ClipsPage'
import { UnauthorizedPage } from '@/pages/UnauthorizedPage'
import { AdminUsersPage } from '@/pages/admin/AdminUsersPage'

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
              <Route path="/profil" element={<ProfilePage />} />

              {/* Yalnızca yönetici */}
              <Route element={<RequireAuth roles={['Yönetici']} />}>
                <Route path="/yonetim/kullanicilar" element={<AdminUsersPage />} />
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

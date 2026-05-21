import RegisterForm from './components/RegisterForm'

export default function App() {
  return (
    <div className="app">
      <header className="app-header">
        <div className="header-content">
          <h1>OpenMRS Notificatiemodule</h1>
          <p>SaaS beheerportaal</p>
        </div>
      </header>
      <main className="app-main">
        <RegisterForm />
      </main>
    </div>
  )
}

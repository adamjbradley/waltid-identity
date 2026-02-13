import CustomCredentialModal from "@/components/walt/modal/CustomCredentialModal";
import {MagnifyingGlassIcon, Cog6ToothIcon, GlobeAltIcon} from "@heroicons/react/24/outline";
import Credential from "@/components/walt/credential/Credential";
import {AvailableCredential} from "@/types/credentials";
import {CredentialsContext, EnvContext} from "@/pages/_app";
import {Inter} from "next/font/google";
import React, {useState} from "react";
import {useRouter} from "next/router";

const inter = Inter({ subsets: ['latin'] });

export default function Home() {
  const [AvailableCredentials] = React.useContext(CredentialsContext);
  const env = React.useContext(EnvContext);
  const router = useRouter();

  const [searchTerm, setSearchTerm] = useState<string>('');
  const [modalVisible, setModalVisible] = useState(false);

  const issuerRegistrarEnabled = (env.NEXT_PUBLIC_ISSUER_REGISTRAR_ENABLED ?? 'false') === 'true';
  const rpRegistrarEnabled = (env.NEXT_PUBLIC_RP_REGISTRAR_ENABLED ?? 'false') === 'true';
  const hasMtMode = issuerRegistrarEnabled || rpRegistrarEnabled;

  const credentials = !searchTerm
    ? AvailableCredentials
    : AvailableCredentials.filter((credential: AvailableCredential) => {
        return credential.title
          .toLowerCase()
          .includes(searchTerm.toLowerCase());
      });

  function handleCredentialSelect(id: string) {
    router.push(`/credentials?ids=${id}`);
  }

  function handleSearchTermChange(e: any) {
    const value = e.target.value;
    setSearchTerm(value);
  }

  return (
    <div>
      <div className="flex flex-col justify-center items-center mt-10">
        <h1 className="text-4xl font-bold text-primary-900 text-center mt-5">
          Walt.id Portal
        </h1>
        <p className="mt-4 text-lg text-primary-900">
          Select a credential to issue or verify
        </p>
        <button
          onClick={() => router.push('/admin/trust-config')}
          className="mt-4 flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm text-gray-500 hover:text-gray-700 hover:bg-gray-100 transition-colors"
        >
          <Cog6ToothIcon className="w-4 h-4" />
          Admin
        </button>
        {issuerRegistrarEnabled && (
          <button
            onClick={() => router.push('/explore')}
            className="mt-2 flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm text-gray-500 hover:text-gray-700 hover:bg-gray-100 transition-colors"
            data-testid="explore-btn"
          >
            <GlobeAltIcon className="w-4 h-4" />
            Explore by Country
          </button>
        )}
        {hasMtMode && (
          <div className="mt-4 flex items-center gap-2 px-4 py-2 bg-blue-50 border border-blue-200 rounded-lg text-sm text-blue-700" data-testid="mt-banner">
            <span className="font-medium">Multi-Tenant Mode</span>
            {issuerRegistrarEnabled && (
              <span className="px-2 py-0.5 bg-blue-100 rounded-full text-xs font-medium">Issuer Registrar</span>
            )}
            {rpRegistrarEnabled && (
              <span className="px-2 py-0.5 bg-blue-100 rounded-full text-xs font-medium">RP Registrar</span>
            )}
          </div>
        )}
      </div>
      <main className="flex flex-col items-center gap-5 justify-between mt-16 md:w-[740px] m-auto">
        <div className="flex flex-row gap-5 w-full px-5">
          <div className="flex flex-row w-full border-b border-b-1 border-gray-200">
            <MagnifyingGlassIcon className="h-6 mt-3 text-gray-500" />
            <input
              type="text"
              className="w-full mt-1 border-none outline-none focus:ring-0 bg-gray-50"
              onChange={handleSearchTermChange}
            />
          </div>
          {/* Commented out for now, because of oidc credentialConfigurationId introduction */}
          {/* <Button size='sm' onClick={() => { setModalVisible(true); }}>Custom Credential</Button> */}
        </div>
        {credentials.length === 0 && (
          <div className="w-full mt-10 text-center">
            No Credential with that name.
          </div>
        )}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-y-10 gap-x-5 mt-10">
          {credentials.map(({ id, title }: AvailableCredential) => (
            <Credential
              id={id}
              title={title}
              onClick={handleCredentialSelect}
              key={id}
            />
          ))}
        </div>
      </main>
      <CustomCredentialModal
        show={modalVisible}
        onClose={() => {
          setModalVisible(!modalVisible);
        }}
      />
    </div>
  );
}

import CustomCredentialModal from "@/components/walt/modal/CustomCredentialModal";
import {MagnifyingGlassIcon} from "@heroicons/react/24/outline";
import Credential from "@/components/walt/credential/Credential";
import {AvailableCredential} from "@/types/credentials";
import {CredentialsContext} from "@/pages/_app";
import {Inter} from "next/font/google";
import React, {useState} from "react";
import {useRouter} from "next/router";

const inter = Inter({ subsets: ['latin'] });

export default function Home() {
  const [AvailableCredentials] = React.useContext(CredentialsContext);
  const router = useRouter();

  const [searchTerm, setSearchTerm] = useState<string>('');
  const [modalVisible, setModalVisible] = useState(false);

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

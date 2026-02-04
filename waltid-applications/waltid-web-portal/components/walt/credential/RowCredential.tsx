import {AvailableCredential, CredentialFormats, DIDMethods, mapFormat, isEudiFormat, ClaimDefinition} from "@/types/credentials";
import EditCredentialModal from "../modal/EditCredentialModal";
import {PencilSquareIcon} from "@heroicons/react/24/outline";
import Dropdown from "@/components/walt/forms/Dropdown";
import ClaimsEditor from "@/components/walt/forms/ClaimsEditor";
import React from "react";

type Props = {
  credentialToEdit: AvailableCredential;
  credentialsToIssue: AvailableCredential[];
  setCredentialsToIssue: (credentials: AvailableCredential[]) => void;
};

export default function RowCredential({
  credentialToEdit,
  credentialsToIssue,
  setCredentialsToIssue,
}: Props) {
  const [credentialSubject, setCredentialSubject] = React.useState(
    credentialToEdit.offer.credentialSubject
  );
  const [selectedFormat, setSelectedFormat] = React.useState(
    CredentialFormats[0]
  );
  const [selectedDID, setSelectedDID] = React.useState(DIDMethods[0]);
  const [modalVisible, setModalVisible] = React.useState(false);
  // Initialize claims from defaultClaims or empty array
  const [claims, setClaims] = React.useState<ClaimDefinition[]>(
    credentialToEdit.defaultClaims || []
  );

  React.useEffect(() => {
    setCredentialsToIssue(
      credentialsToIssue.map((credential) => {
        if (credential.offer.id == credentialToEdit.offer.id) {
          let updatedCredential = { ...credential };

          if (credentialSubject !== credential.offer.credentialSubject) {
            updatedCredential.offer.credentialSubject = credentialSubject;
          }
          updatedCredential.selectedFormat = selectedFormat;
          updatedCredential.selectedDID = selectedDID;
          updatedCredential.editedClaims = claims;

          return updatedCredential;
        } else {
          let updatedCredential = { ...credential };
          updatedCredential.selectedFormat = selectedFormat;
          return updatedCredential;
        }
      })
    );
  }, [credentialSubject, selectedFormat, selectedDID, claims]);

  return (
    <>
      <div className="lg:flex flex-row gap-5 justify-between">
        <div className="flex flex-row gap-5 items-center">
          <div className="hidden sm:block bg-primary-400 w-[45px] h-[28px] rounded-lg"></div>
          <span className="text-gray-900 text-lg text-left">
            {credentialToEdit.title}
          </span>
          {(() => {
            try {
              return isEudiFormat(mapFormat(selectedFormat)) && (
                <span className="px-2 py-0.5 text-xs font-medium bg-blue-100 text-blue-800 rounded">
                  EUDI
                </span>
              );
            } catch {
              return null;
            }
          })()}
          <PencilSquareIcon
            onClick={() => {
              setModalVisible(true);
            }}
            className="h-4 text-gray-500 hover:text-primary-400 cursor-pointer"
          />
        </div>
        <div className="flex flex-row items-center gap-3 lg:w-5/12">
          <div className="hidden lg:block w-[2px] h-[2px] bg-gray-200"></div>
          <div className="w-full">
            <Dropdown
              values={CredentialFormats}
              selected={selectedFormat}
              setSelected={setSelectedFormat}
            />
          </div>
          <div className="w-full">
            <Dropdown
              values={DIDMethods}
              selected={selectedDID}
              setSelected={setSelectedDID}
            />
          </div>
        </div>
      </div>
      {/* Render ClaimsEditor only for EUDI formats */}
      {(() => {
        try {
          const format = mapFormat(selectedFormat);
          if (isEudiFormat(format)) {
            return (
              <ClaimsEditor
                credentialTitle={credentialToEdit.title}
                credentialId={credentialToEdit.id}
                claims={claims}
                onChange={setClaims}
              />
            );
          }
          return null;
        } catch {
          return null;
        }
      })()}
      <EditCredentialModal
        show={modalVisible}
        onClose={() => {
          setModalVisible(false);
        }}
        credentialSubject={credentialSubject}
        setCredentialSubject={setCredentialSubject}
      />
    </>
  );
}
